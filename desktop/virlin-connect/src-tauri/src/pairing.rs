//! Isolated one-time desktop TLS pairing listener. No app data is exposed here.
//! Deliberately not wired into the current scrcpy UI until phone confirmation exists.

use getrandom::fill;
use rcgen::{generate_simple_self_signed, CertifiedKey};
use rustls::{pki_types::{CertificateDer, PrivatePkcs8KeyDer}, ServerConfig, ServerConnection, StreamOwned};
use sha2::{Digest, Sha256};
use std::{io::{Read, Write}, net::{Ipv4Addr, SocketAddrV4, TcpListener, TcpStream}, sync::Arc, time::{Duration, Instant}};
use subtle::ConstantTimeEq;

const VALIDITY: Duration = Duration::from_secs(300);
const IO_TIMEOUT: Duration = Duration::from_secs(5);

pub struct PairingListener {
    listener: TcpListener,
    tls: Arc<ServerConfig>,
    secret_hex: String,
    fingerprint_hex: String,
    certificate_der: Vec<u8>,
    expires: Instant,
}

impl PairingListener {
    pub fn bind(ip: Ipv4Addr) -> Result<Self, String> {
        if !is_private_lan(ip) { return Err("Select a private local network address".into()); }
        Self::bind_internal(ip)
    }

    fn bind_internal(ip: Ipv4Addr) -> Result<Self, String> {
        let mut secret = [0u8; 32];
        fill(&mut secret).map_err(|e| e.to_string())?;
        let CertifiedKey { cert, signing_key } = generate_simple_self_signed(vec![ip.to_string()])
            .map_err(|e| e.to_string())?;
        let cert_der = cert.der().to_vec();
        let key = PrivatePkcs8KeyDer::from(signing_key.serialize_der());
        let provider = Arc::new(rustls::crypto::ring::default_provider());
        let tls = ServerConfig::builder_with_provider(provider)
            .with_safe_default_protocol_versions().map_err(|e| e.to_string())?
            .with_no_client_auth()
            .with_single_cert(vec![CertificateDer::from(cert_der.clone())], key.into())
            .map_err(|e| e.to_string())?;
        let listener = TcpListener::bind(SocketAddrV4::new(ip, 0)).map_err(|e| e.to_string())?;
        listener.set_nonblocking(true).map_err(|e| e.to_string())?;
        Ok(Self {
            listener,
            tls: Arc::new(tls),
            secret_hex: hex(&secret),
            fingerprint_hex: hex(&Sha256::digest(cert_der)),
            certificate_der: cert.der().to_vec(),
            expires: Instant::now() + VALIDITY,
        })
    }

    pub fn invitation(&self, ip: Ipv4Addr, now_epoch_seconds: u64) -> Result<String, String> {
        let bound = self.listener.local_addr().map_err(|e| e.to_string())?;
        if bound.ip() != ip { return Err("Pairing address changed".into()); }
        let expiry = now_epoch_seconds.checked_add(VALIDITY.as_secs()).ok_or("Invalid clock")?;
        Ok(format!("virlin-pair://connect/v1?host={ip}&port={}&cert={}&secret={}&expires={expiry}",
            bound.port(), self.fingerprint_hex, self.secret_hex))
    }

    /// Waits for one authenticated phone. Caller owns the resulting TLS stream.
    /// Wrong attempts are bounded; no state or app data is sent before authentication.
    pub fn accept_once(&self) -> Result<StreamOwned<ServerConnection, TcpStream>, String> {
        let mut attempts = 0;
        while Instant::now() < self.expires {
            let (tcp, _) = match self.listener.accept() {
                Ok(value) => value,
                Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                    std::thread::sleep(Duration::from_millis(50));
                    continue;
                }
                Err(e) => return Err(e.to_string()),
            };
            attempts += 1;
            if attempts > 3 { return Err("Too many pairing attempts".into()); }
            tcp.set_nonblocking(false).map_err(|e| e.to_string())?;
            tcp.set_read_timeout(Some(IO_TIMEOUT)).map_err(|e| e.to_string())?;
            tcp.set_write_timeout(Some(IO_TIMEOUT)).map_err(|e| e.to_string())?;
            let connection = ServerConnection::new(self.tls.clone()).map_err(|e| e.to_string())?;
            let mut stream = StreamOwned::new(connection, tcp);
            let mut line = Vec::with_capacity(70);
            for _ in 0..70 {
                let mut byte = [0];
                if stream.read_exact(&mut byte).is_err() { break; }
                line.push(byte[0]);
                if byte[0] == b'\n' { break; }
            }
            let expected = format!("PAIR {}\n", self.secret_hex);
            if line.len() == expected.len() && bool::from(line.ct_eq(expected.as_bytes())) {
                stream.write_all(b"PAIR-OK\n").map_err(|e| e.to_string())?;
                stream.flush().map_err(|e| e.to_string())?;
                return Ok(stream);
            }
        }
        Err("Pairing expired or failed".into())
    }
}

pub fn is_private_lan(ip: Ipv4Addr) -> bool {
    let [a, b, _, _] = ip.octets();
    a == 10 || (a == 172 && (16..=31).contains(&b)) ||
        (a == 192 && b == 168) || (a == 169 && b == 254)
}

fn hex(bytes: &[u8]) -> String {
    const DIGITS: &[u8; 16] = b"0123456789abcdef";
    let mut result = String::with_capacity(bytes.len() * 2);
    for b in bytes {
        result.push(DIGITS[(b >> 4) as usize] as char);
        result.push(DIGITS[(b & 15) as usize] as char);
    }
    result
}

#[cfg(test)]
mod tests {
    use super::*;
    use rustls::{pki_types::ServerName, ClientConfig, ClientConnection, RootCertStore};

    #[test]
    fn only_private_lan_is_advertised() {
        assert!(is_private_lan(Ipv4Addr::new(192, 168, 1, 2)));
        assert!(is_private_lan(Ipv4Addr::new(172, 20, 0, 1)));
        assert!(!is_private_lan(Ipv4Addr::new(8, 8, 8, 8)));
        assert!(!is_private_lan(Ipv4Addr::LOCALHOST));
    }

    #[test]
    fn invitation_has_fresh_secret_and_certificate_per_session() {
        let ip = Ipv4Addr::new(127, 0, 0, 1);
        let first = PairingListener::bind_internal(ip).unwrap();
        let second = PairingListener::bind_internal(ip).unwrap();
        let a = first.invitation(ip, 1_790_000_000).unwrap();
        let b = second.invitation(ip, 1_790_000_000).unwrap();
        assert!(a.starts_with("virlin-pair://connect/v1?"));
        assert_ne!(a, b);
        assert!(a.contains("expires=1790000300"));
    }

    #[test]
    fn pinned_tls_client_can_complete_one_time_handshake() {
        let ip = Ipv4Addr::LOCALHOST;
        let mut listener = PairingListener::bind_internal(ip).unwrap();
        listener.expires = Instant::now() + Duration::from_secs(2);
        let port = listener.listener.local_addr().unwrap().port();
        let secret = listener.secret_hex.clone();
        let cert = listener.certificate_der.clone();
        let server = std::thread::spawn(move || listener.accept_once().map(|_| ()));

        let mut roots = RootCertStore::empty();
        roots.add(CertificateDer::from(cert)).unwrap();
        let provider = Arc::new(rustls::crypto::ring::default_provider());
        let config = ClientConfig::builder_with_provider(provider)
            .with_safe_default_protocol_versions().unwrap()
            .with_root_certificates(roots)
            .with_no_client_auth();
        let connection = ClientConnection::new(Arc::new(config), ServerName::try_from("127.0.0.1").unwrap()).unwrap();
        let tcp = TcpStream::connect((ip, port)).unwrap();
        tcp.set_read_timeout(Some(IO_TIMEOUT)).unwrap();
        let mut client = StreamOwned::new(connection, tcp);
        client.write_all(format!("PAIR {secret}\n").as_bytes()).unwrap();
        let mut reply = [0; 8];
        let response = client.read_exact(&mut reply);
        let server_result = server.join().unwrap();
        assert!(server_result.is_ok(), "server error: {server_result:?}");
        response.unwrap();
        assert_eq!(&reply, b"PAIR-OK\n");
    }
}
