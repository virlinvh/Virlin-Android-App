#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod pairing;

use serde::Serialize;
use std::collections::HashMap;
use std::net::{IpAddr, Ipv4Addr};
use std::path::PathBuf;
use std::process::{Command, Stdio};
use std::sync::{Arc, Mutex, atomic::{AtomicBool, Ordering}};
use std::io::Read;
use std::time::{SystemTime, UNIX_EPOCH};
use tauri::{Manager, State, path::BaseDirectory};

#[derive(Default)]
struct PairingState(Mutex<Option<PairingSession>>);

struct PairingSession {
    cancelled: Arc<AtomicBool>,
    status: Arc<Mutex<String>>,
}

#[derive(Serialize)]
struct LanAddress { name: String, address: String, kind: String }

#[derive(Serialize)]
struct PairingOffer { rows: Vec<String>, expires_at: u64, address: String }

#[tauri::command]
fn list_lan_addresses() -> Result<Vec<LanAddress>, String> {
    let interfaces = local_ip_address::list_afinet_netifas().map_err(|e| e.to_string())?;
    let descriptions = adapter_descriptions();
    let mut addresses: Vec<_> = interfaces.into_iter().filter_map(|(name, ip)| match ip {
        IpAddr::V4(address) if pairing::is_private_lan(address) => {
            let kind = if descriptions.get(&name).is_some_and(|description| is_usb_tether_description(description)) {
                "usb_tether"
            } else { "network" };
            Some(LanAddress { name, address: address.to_string(), kind: kind.into() })
        }
        _ => None,
    }).collect();
    addresses.sort_by(|a, b| a.address.cmp(&b.address));
    Ok(addresses)
}

fn is_usb_tether_description(description: &str) -> bool {
    let description = description.to_ascii_lowercase();
    ["remote ndis", "rndis", "usb tether", "usb ethernet"]
        .iter().any(|term| description.contains(term))
}

#[cfg(windows)]
fn adapter_descriptions() -> HashMap<String, String> {
    use windows_sys::Win32::NetworkManagement::IpHelper::{GetAdaptersAddresses, IP_ADAPTER_ADDRESSES_LH, GAA_FLAG_SKIP_UNICAST};
    let mut bytes = 15_000u32;
    for _ in 0..3 {
        // u64 gives the WinAPI buffer suitable alignment for IP_ADAPTER_ADDRESSES_LH.
        let mut buffer = vec![0u64; (bytes as usize).div_ceil(8)];
        let ptr = buffer.as_mut_ptr() as *mut IP_ADAPTER_ADDRESSES_LH;
        let result = unsafe { GetAdaptersAddresses(0, GAA_FLAG_SKIP_UNICAST, std::ptr::null(), ptr, &mut bytes) };
        if result == 111 { continue; } // ERROR_BUFFER_OVERFLOW
        if result != 0 { break; }
        let mut descriptions = HashMap::new();
        let mut current = ptr;
        for _ in 0..256 {
            if current.is_null() { break; }
            let adapter = unsafe { &*current };
            let name = unsafe { utf16_pointer(adapter.FriendlyName) };
            let description = unsafe { utf16_pointer(adapter.Description) };
            descriptions.insert(name, description);
            current = adapter.Next;
        }
        return descriptions;
    }
    HashMap::new()
}

#[cfg(windows)]
unsafe fn utf16_pointer(pointer: *const u16) -> String {
    if pointer.is_null() { return String::new(); }
    let mut len = 0;
    while len < 512 && unsafe { *pointer.add(len) } != 0 { len += 1; }
    String::from_utf16_lossy(unsafe { std::slice::from_raw_parts(pointer, len) })
}

#[cfg(not(windows))]
fn adapter_descriptions() -> HashMap<String, String> { HashMap::new() }

#[tauri::command]
fn start_pairing(address: String, state: State<PairingState>) -> Result<PairingOffer, String> {
    let ip: Ipv4Addr = address.parse().map_err(|_| "Invalid local address")?;
    if !list_lan_addresses()?.iter().any(|entry| entry.address == address) {
        return Err("Choose a listed local network address".into());
    }
    let mut guard = state.0.lock().map_err(|_| "Pairing state unavailable")?;
    if guard.is_some() { return Err("Disconnect the current session first".into()); }
    let listener = pairing::PairingListener::bind(ip)?;
    let now = SystemTime::now().duration_since(UNIX_EPOCH).map_err(|e| e.to_string())?.as_secs();
    let invitation = listener.invitation(ip, now)?;
    let qr = qrcodegen::QrCode::encode_text(&invitation, qrcodegen::QrCodeEcc::Medium)
        .map_err(|_| "Could not create pairing QR")?;
    let rows = (0..qr.size()).map(|y| (0..qr.size()).map(|x| if qr.get_module(x, y) { '1' } else { '0' }).collect()).collect();
    let cancelled = Arc::new(AtomicBool::new(false));
    let status = Arc::new(Mutex::new("Waiting for phone approval".to_string()));
    let thread_cancel = cancelled.clone();
    let thread_status = status.clone();
    std::thread::spawn(move || {
        match listener.accept_with_cancel(&thread_cancel) {
            Ok(mut connection) => {
                *thread_status.lock().unwrap() = "Connected — secure pairing only".into();
                let _ = connection.sock.set_read_timeout(Some(std::time::Duration::from_secs(1)));
                let mut buffer = [0u8; 1];
                while !thread_cancel.load(Ordering::Relaxed) {
                    match connection.read(&mut buffer) {
                        Ok(0) => break,
                        Ok(_) => break, // No application commands are accepted in this slice.
                        Err(e) if e.kind() == std::io::ErrorKind::TimedOut || e.kind() == std::io::ErrorKind::WouldBlock => continue,
                        Err(_) => break,
                    }
                }
                *thread_status.lock().unwrap() = "Disconnected".into();
            }
            Err(error) => *thread_status.lock().unwrap() = error,
        }
    });
    *guard = Some(PairingSession { cancelled, status });
    Ok(PairingOffer { rows, expires_at: now + 300, address })
}

#[tauri::command]
fn pairing_status(state: State<PairingState>) -> Result<String, String> {
    let guard = state.0.lock().map_err(|_| "Pairing state unavailable")?;
    Ok(guard.as_ref().map(|session| session.status.lock().unwrap().clone()).unwrap_or_else(|| "Not connected".into()))
}

#[tauri::command]
fn cancel_pairing(state: State<PairingState>) -> Result<(), String> {
    if let Some(session) = state.0.lock().map_err(|_| "Pairing state unavailable")?.take() {
        session.cancelled.store(true, Ordering::Relaxed);
    }
    Ok(())
}

const PACKAGE: &str = "com.virlin.app";

#[derive(Serialize)]
struct Device {
    serial: String,
    state: String,
    kind: String,
    model: Option<String>,
}

fn tool_dir(app: &tauri::AppHandle) -> Result<PathBuf, String> {
    app.path()
        .resolve("resources/scrcpy", BaseDirectory::Resource)
        .map_err(|e| e.to_string())
}

fn adb(app: &tauri::AppHandle) -> Result<PathBuf, String> {
    let path = tool_dir(app)?.join("adb.exe");
    if path.is_file() { Ok(path) } else { Err("Bundled Android tools are missing. Reinstall Virlin Connect.".into()) }
}

fn adb_output(app: &tauri::AppHandle, args: &[&str]) -> Result<String, String> {
    let output = Command::new(adb(app)?)
        .args(args)
        .creation_flags_no_window()
        .output()
        .map_err(|e| format!("Could not run Android tools: {e}"))?;
    let stdout = String::from_utf8_lossy(&output.stdout).to_string();
    if output.status.success() { Ok(stdout) } else {
        Err(String::from_utf8_lossy(&output.stderr).trim().to_string())
    }
}

#[cfg(windows)]
trait NoWindow {
    fn creation_flags_no_window(&mut self) -> &mut Self;
}
#[cfg(windows)]
impl NoWindow for Command {
    fn creation_flags_no_window(&mut self) -> &mut Self {
        use std::os::windows::process::CommandExt;
        self.creation_flags(0x08000000)
    }
}

#[tauri::command]
fn list_devices(app: tauri::AppHandle) -> Result<Vec<Device>, String> {
    let raw = adb_output(&app, &["devices", "-l"])?;
    Ok(parse_devices(&raw))
}

fn parse_devices(raw: &str) -> Vec<Device> {
    let mut devices = Vec::new();
    for line in raw.lines().skip(1) {
        let mut parts = line.split_whitespace();
        let (Some(serial), Some(state)) = (parts.next(), parts.next()) else { continue };
        if serial.starts_with('*') { continue }
        let model = line.split_whitespace().find_map(|p| p.strip_prefix("model:")).map(|s| s.replace('_', " "));
        let kind = if serial.starts_with("emulator-") || model.as_deref().is_some_and(|m| m.starts_with("sdk ")) {
            "Emulator"
        } else if serial.contains(':') { "Wi-Fi" } else { "USB" };
        devices.push(Device { serial: serial.into(), state: state.into(), kind: kind.into(), model });
    }
    devices
}

#[tauri::command]
fn open_virlin(app: tauri::AppHandle, serial: String) -> Result<(), String> {
    // A serial supplied by the UI is never passed to a shell, but still must match
    // a currently authorized physical device. This also prevents stale selections.
    let devices = list_devices(app.clone())?;
    let device = devices.iter().find(|d| d.serial == serial && d.state == "device" && d.kind == "USB")
        .ok_or("Select an authorized physical phone and try again.")?;
    let installed = adb_output(&app, &["-s", &device.serial, "shell", "pm", "path", PACKAGE])?;
    if !installed.lines().any(|line| line.starts_with("package:")) {
        return Err("Virlin is not installed on this phone. Install it first; Connect will not alter your app or data.".into());
    }
    adb_output(&app, &["-s", &device.serial, "shell", "monkey", "-p", PACKAGE, "-c", "android.intent.category.LAUNCHER", "1"])?;
    let dir = tool_dir(&app)?;
    let scrcpy = dir.join("scrcpy.exe");
    if !scrcpy.is_file() { return Err("Bundled mirroring tool is missing. Reinstall Virlin Connect.".into()) }
    Command::new(scrcpy)
        .arg(format!("--serial={}", device.serial))
        .env("ADB", adb(&app)?)
        .current_dir(dir)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .map_err(|e| format!("Could not open the mirrored window: {e}"))?;
    Ok(())
}

fn main() {
    tauri::Builder::default()
        .manage(PairingState::default())
        .invoke_handler(tauri::generate_handler![list_lan_addresses, start_pairing, pairing_status, cancel_pairing, list_devices, open_virlin])
        .run(tauri::generate_context!())
        .expect("Virlin Connect failed to start");
}

#[cfg(test)]
mod device_tests {
    use super::{is_usb_tether_description, parse_devices};

    #[cfg(windows)]
    #[test]
    fn windows_adapter_descriptions_have_names() {
        let descriptions = super::adapter_descriptions();
        assert!(descriptions.keys().all(|name| !name.is_empty()));
        assert!(descriptions.values().all(|description| !description.is_empty()));
        println!("Windows adapters: {descriptions:?}");
    }

    #[test]
    fn usb_network_detection_uses_adapter_description() {
        assert!(is_usb_tether_description("SAMSUNG Mobile USB Remote NDIS Network Device #2"));
        assert!(is_usb_tether_description("Android RNDIS Ethernet Adapter"));
        assert!(!is_usb_tether_description("Killer Wi-Fi 6 Adapter"));
    }

    #[test]
    fn usb_detection_excludes_emulators_and_wifi_transports() {
        let devices = parse_devices("List of devices attached\nABC123 device product:panther model:Pixel_8\nemulator-5554 device model:sdk_gphone64_x86_64\n192.168.1.12:5555 device model:Pixel_8\nDEF456 unauthorized\n");
        assert_eq!(devices.len(), 4);
        assert_eq!(devices[0].kind, "USB");
        assert_eq!(devices[0].model.as_deref(), Some("Pixel 8"));
        assert_eq!(devices[1].kind, "Emulator");
        assert_eq!(devices[2].kind, "Wi-Fi");
        assert_eq!(devices[3].kind, "USB");
        assert_eq!(devices[3].state, "unauthorized");
    }
}
