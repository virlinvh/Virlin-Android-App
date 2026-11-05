#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod pairing;
mod offline_copy;

use serde::Serialize;
use std::collections::HashMap;
use std::net::{IpAddr, Ipv4Addr};
use std::sync::{Arc, Mutex, mpsc, atomic::{AtomicBool, Ordering}};
use std::io::{Read, Write};
use std::time::{SystemTime, UNIX_EPOCH};
use tauri::{Manager, State};

#[derive(Default)]
struct PairingState(Mutex<Option<PairingSession>>);

struct PairingSession {
    cancelled: Arc<AtomicBool>,
    status: Arc<Mutex<String>>,
    requests: mpsc::Sender<(String, mpsc::Sender<Result<String, String>>)>,
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
    // A dropped/expired connection leaves its session handle behind until the
    // UI explicitly cancels it. Allow a fresh QR after that terminal state.
    if guard.as_ref().is_some_and(|session| {
        let status = session.status.lock().unwrap();
        !status.starts_with("Connected") && !status.starts_with("Waiting for phone approval")
    }) {
        if let Some(ended) = guard.take() { ended.cancelled.store(true, Ordering::Relaxed); }
    }
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
    let (request_tx, request_rx) = mpsc::channel::<(String, mpsc::Sender<Result<String, String>>) >();
    std::thread::spawn(move || {
        match listener.accept_with_cancel(&thread_cancel) {
            Ok(mut connection) => {
                *thread_status.lock().unwrap() = "Connected — project browser ready".into();
                let _ = connection.sock.set_read_timeout(Some(std::time::Duration::from_secs(1)));
                while !thread_cancel.load(Ordering::Relaxed) {
                    match request_rx.recv_timeout(std::time::Duration::from_millis(100)) {
                        Ok((request, reply)) => {
                            let _ = connection.sock.set_read_timeout(Some(std::time::Duration::from_secs(60)));
                            let result = (|| {
                                if request.len() > 512 * 1024 - 1 { return Err("Request too large".into()); }
                                connection.write_all(request.as_bytes()).map_err(|e| e.to_string())?;
                                connection.write_all(b"\n").map_err(|e| e.to_string())?;
                                connection.flush().map_err(|e| e.to_string())?;
                                read_bounded_line(&mut connection, 1024 * 1024)
                            })();
                            let failed = result.is_err();
                            let _ = reply.send(result);
                            let _ = connection.sock.set_read_timeout(Some(std::time::Duration::from_secs(1)));
                            if failed { break; }
                        }
                        Err(mpsc::RecvTimeoutError::Timeout) => {
                            let mut byte = [0u8; 1];
                            match connection.read(&mut byte) {
                                Ok(0) => break,
                                Ok(_) => break, // The phone never sends unsolicited workspace data.
                                Err(e) if matches!(e.kind(), std::io::ErrorKind::TimedOut | std::io::ErrorKind::WouldBlock) => {},
                                Err(_) => break,
                            }
                        }
                        Err(mpsc::RecvTimeoutError::Disconnected) => break,
                    }
                }
                *thread_status.lock().unwrap() = "Disconnected".into();
            }
            Err(error) => *thread_status.lock().unwrap() = error,
        }
    });
    *guard = Some(PairingSession { cancelled, status, requests: request_tx });
    Ok(PairingOffer { rows, expires_at: now + 300, address })
}

fn read_bounded_line(input: &mut impl Read, limit: usize) -> Result<String, String> {
    let mut bytes = Vec::new();
    loop {
        let mut byte = [0u8];
        input.read_exact(&mut byte).map_err(|e| e.to_string())?;
        if byte[0] == b'\n' { return String::from_utf8(bytes).map_err(|_| "Invalid response encoding".into()); }
        if bytes.len() >= limit { return Err("Project response exceeds size limit".into()); }
        bytes.push(byte[0]);
    }
}

#[tauri::command]
fn project_tree(state: State<PairingState>) -> Result<String, String> {
    workspace_request(&state, serde_json::json!({"v": 1, "type": "get_tree"}).to_string())
}

#[tauri::command]
fn map_appearance(project_id: String, state: State<PairingState>) -> Result<String, String> {
    if !valid_map_id(&project_id) { return Err("Invalid project ID".into()); }
    workspace_request(&state, serde_json::json!({"v": 1, "type": "get_map_appearance",
        "projectId": project_id}).to_string())
}

fn valid_map_id(id: &str) -> bool {
    !id.is_empty() && id.len() <= 128 &&
        id.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-')
}

#[tauri::command]
fn set_map_appearance(project_id: String, node_id: String, operation: String,
    color: Option<u64>, width: Option<f32>, expected_color: Option<u64>,
    expected_width: Option<f32>, has_expected: bool, key: String,
    state: State<PairingState>) -> Result<String, String> {
    if !valid_map_id(&project_id) || !valid_map_id(&node_id) || !is_uuid_key(&key) ||
        !matches!(operation.as_str(), "color" | "width") ||
        color.is_some_and(|value| !(0xff000000..=0xffffffff).contains(&value)) ||
        width.is_some_and(|value| !value.is_finite() || !(110.0..=560.0).contains(&value)) ||
        expected_color.is_some_and(|value| !(0xff000000..=0xffffffff).contains(&value)) ||
        expected_width.is_some_and(|value| !value.is_finite() || !(110.0..=560.0).contains(&value)) {
        return Err("Invalid map appearance edit".into());
    }
    workspace_request(&state, serde_json::json!({"v": 1, "type": "set_map_appearance",
        "projectId": project_id, "nodeId": node_id, "operation": operation,
        "color": color, "width": width, "expectedColor": expected_color,
        "expectedWidth": expected_width, "hasExpected": has_expected,
        "key": key}).to_string())
}

#[tauri::command]
fn map_place(project_id: String, source_id: String, target_parent_id: String,
    insert_before_id: Option<String>, operation: String, expected_revision: String, key: String,
    state: State<PairingState>) -> Result<String, String> {
    if !valid_map_id(&project_id) || !valid_map_id(&source_id) ||
        !valid_map_id(&target_parent_id) ||
        insert_before_id.as_ref().is_some_and(|id| !valid_map_id(id)) ||
        !matches!(operation.as_str(), "MOVE" | "COPY") || expected_revision.parse::<i64>().is_err() ||
        !is_uuid_key(&key) { return Err("Invalid map placement".into()); }
    workspace_request(&state, serde_json::json!({"v": 1, "type": "map_place",
        "projectId": project_id, "sourceId": source_id,
        "targetParentId": target_parent_id, "insertBeforeId": insert_before_id,
        "operation": operation, "expectedRevision": expected_revision,
        "key": key}).to_string())
}

#[tauri::command]
fn map_delete(project_id: String, node_id: String, kind: String,
    confirm_title: String, key: String, state: State<PairingState>) -> Result<String, String> {
    if !valid_map_id(&project_id) || !valid_map_id(&node_id) ||
        !matches!(kind.as_str(), "project" | "workstream" | "task") ||
        confirm_title.is_empty() || confirm_title.chars().count() > 200 || !is_uuid_key(&key) {
        return Err("Invalid map delete request".into());
    }
    workspace_request(&state, serde_json::json!({"v": 1, "type": "map_delete",
        "projectId": project_id, "nodeId": node_id, "kind": kind,
        "confirmTitle": confirm_title, "key": key}).to_string())
}

#[tauri::command]
fn map_rename(project_id: String, node_id: String, kind: String,
    title: String, key: String, state: State<PairingState>) -> Result<String, String> {
    if !valid_map_id(&project_id) || !valid_map_id(&node_id) ||
        !matches!(kind.as_str(), "project" | "workstream" | "task") ||
        title.trim().is_empty() || title.chars().count() > 200 || !is_uuid_key(&key) {
        return Err("Invalid map rename request".into());
    }
    workspace_request(&state, serde_json::json!({"v": 1, "type": "map_rename",
        "projectId": project_id, "nodeId": node_id, "kind": kind,
        "title": title, "key": key}).to_string())
}

#[tauri::command]
fn project_knowledge(project_id: String, state: State<PairingState>) -> Result<String, String> {
    if project_id.is_empty() || project_id.len() > 128 ||
        !project_id.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-') {
        return Err("Invalid project ID".into());
    }
    workspace_request(&state, serde_json::json!({"v": 1, "type": "get_knowledge", "projectId": project_id}).to_string())
}

#[tauri::command]
fn knowledge_capabilities(state: State<PairingState>) -> Result<String, String> {
    workspace_request(&state, serde_json::json!({"v": 1, "type": "get_capabilities"}).to_string())
}

#[tauri::command]
fn knowledge_item(project_id: String, capture_id: String, state: State<PairingState>) -> Result<String, String> {
    for id in [&project_id, &capture_id] {
        if id.is_empty() || id.len() > 128 ||
            !id.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-') {
            return Err("Invalid knowledge ID".into());
        }
    }
    workspace_request(&state, serde_json::json!({"v": 1, "type": "get_knowledge_item",
        "projectId": project_id, "captureId": capture_id}).to_string())
}

#[tauri::command]
fn voice_chunk(project_id: String, capture_id: String, clip_id: String, offset: u64,
    state: State<PairingState>) -> Result<String, String> {
    for id in [&project_id, &capture_id, &clip_id] {
        if id.is_empty() || id.len() > 128 ||
            !id.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-') {
            return Err("Invalid voice ID".into());
        }
    }
    if offset > 20 * 1024 * 1024 { return Err("Clip is too large".into()); }
    workspace_request(&state, serde_json::json!({"v": 1, "type": "read_voice_chunk",
        "projectId": project_id, "captureId": capture_id, "clipId": clip_id,
        "offset": offset}).to_string())
}

#[tauri::command]
fn upload_note_image(project_id: String, upload_id: String, total_bytes: u64, offset: u64,
    sha256: String, data: String, state: State<PairingState>) -> Result<String, String> {
    if project_id.is_empty() || project_id.len() > 128 ||
        !project_id.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-') ||
        !is_uuid_key(&upload_id) || total_bytes == 0 || total_bytes > 25 * 1024 * 1024 ||
        offset >= total_bytes || sha256.len() != 64 ||
        !sha256.bytes().all(|b| b.is_ascii_hexdigit()) || data.len() > 90_000 {
        return Err("Invalid image upload".into());
    }
    workspace_request(&state, serde_json::json!({"v": 1, "type": "upload_note_image",
        "projectId": project_id, "uploadId": upload_id, "totalBytes": total_bytes,
        "offset": offset, "sha256": sha256, "data": data}).to_string())
}

#[tauri::command]
fn note_image_chunk(project_id: String, capture_id: String, image_path: String, offset: u64,
    state: State<PairingState>) -> Result<String, String> {
    if [&project_id, &capture_id].iter().any(|id| id.is_empty() || id.len() > 128 ||
        !id.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-')) ||
        !is_uuid_key(&image_path) || offset > 25 * 1024 * 1024 {
        return Err("Invalid note image".into());
    }
    workspace_request(&state, serde_json::json!({"v": 1, "type": "read_note_image_chunk",
        "projectId": project_id, "captureId": capture_id,
        "imagePath": image_path, "offset": offset}).to_string())
}

#[tauri::command]
fn save_knowledge_note(project_id: String, capture_id: String, expected_revision: i32,
    title: Option<String>, blocks: serde_json::Value, key: String,
    state: State<PairingState>) -> Result<String, String> {
    for id in [&project_id, &capture_id] {
        if id.is_empty() || id.len() > 128 ||
            !id.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-') {
            return Err("Invalid knowledge ID".into());
        }
    }
    if !is_uuid_key(&key) || expected_revision < 0 ||
        title.as_ref().is_some_and(|t| t.chars().count() > 200) ||
        !blocks.as_array().is_some_and(|items| items.len() <= 500) {
        return Err("Invalid note edit".into());
    }
    let request = serde_json::json!({"v": 1, "type": "save_knowledge_note", "projectId": project_id,
        "captureId": capture_id, "expectedRevision": expected_revision, "title": title,
        "blocks": blocks, "key": key}).to_string();
    if request.len() > 512 * 1024 { return Err("Note edit is too large".into()); }
    workspace_request(&state, request)
}

#[tauri::command]
fn save_knowledge_prompt(project_id: String, capture_id: String, expected_token: String,
    title: Option<String>, description: Option<String>, tags: Vec<String>, mode: String,
    language: Option<String>, content: String, response_text: Option<String>, key: String,
    state: State<PairingState>) -> Result<String, String> {
    for id in [&project_id, &capture_id] {
        if id.is_empty() || id.len() > 128 ||
            !id.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-') {
            return Err("Invalid knowledge ID".into());
        }
    }
    if !is_uuid_key(&key) || expected_token.len() != 64 ||
        !expected_token.bytes().all(|b| b.is_ascii_hexdigit()) ||
        title.as_ref().is_some_and(|t| t.chars().count() > 200) ||
        description.as_ref().is_some_and(|d| d.chars().count() > 4096) ||
        content.chars().count() > 32768 || response_text.as_ref().is_some_and(|r| r.chars().count() > 32768) ||
        language.as_ref().is_some_and(|l| l.chars().count() > 40) ||
        !matches!(mode.as_str(), "PROMPT" | "CODE") ||
        tags.len() > 20 || tags.iter().any(|tag| tag.chars().count() > 80) {
        return Err("Invalid prompt edit".into());
    }
    workspace_request(&state, serde_json::json!({"v": 1, "type": "save_knowledge_prompt",
        "projectId": project_id, "captureId": capture_id, "expectedToken": expected_token,
        "title": title, "description": description, "tags": tags, "mode": mode,
        "language": language, "content": content, "responseText": response_text, "key": key}).to_string())
}

#[tauri::command]
fn save_knowledge_link(project_id: String, capture_id: String, expected_token: String,
    title: Option<String>, url: String, content: String, key: String,
    state: State<PairingState>) -> Result<String, String> {
    let valid_id = |id: &str| !id.is_empty() && id.len() <= 128 &&
        id.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-');
    if !valid_id(&project_id) || !valid_id(&capture_id) || !is_uuid_key(&key) ||
        expected_token.len() != 64 || !expected_token.bytes().all(|b| b.is_ascii_hexdigit()) ||
        title.as_ref().is_some_and(|t| t.chars().count() > 200) ||
        url.len() > 2048 || content.chars().count() > 32768 {
        return Err("Invalid link edit".into());
    }
    workspace_request(&state, serde_json::json!({"v": 1, "type": "save_knowledge_link",
        "projectId": project_id, "captureId": capture_id, "expectedToken": expected_token,
        "title": title, "url": url, "content": content, "key": key}).to_string())
}

#[tauri::command]
fn create_knowledge(project_id: String, task_id: Option<String>, kind: String, title: Option<String>, content: String,
    url: Option<String>, description: Option<String>, tags: Option<Vec<String>>, mode: Option<String>,
    language: Option<String>, response_text: Option<String>, note_blocks: Option<serde_json::Value>, key: String, state: State<PairingState>) -> Result<String, String> {
    if project_id.is_empty() || project_id.len() > 128 ||
        !project_id.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-') {
        return Err("Invalid project ID".into());
    }
    if task_id.as_ref().is_some_and(|id| id.is_empty() || id.len() > 128 ||
        !id.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-')) {
        return Err("Invalid task ID".into());
    }
    if !matches!(kind.as_str(), "note" | "checklist" | "steps" | "prompt" | "link") {
        return Err("Unsupported knowledge type".into());
    }
    if !is_uuid_key(&key) || title.as_ref().is_some_and(|t| t.chars().count() > 200) ||
        content.chars().count() > 32768 || url.as_ref().is_some_and(|u| u.chars().count() > 2048) ||
        description.as_ref().is_some_and(|d| d.chars().count() > 4096) ||
        response_text.as_ref().is_some_and(|r| r.chars().count() > 32768) ||
        language.as_ref().is_some_and(|l| l.chars().count() > 40) ||
        mode.as_ref().is_some_and(|m| m != "PROMPT" && m != "CODE") ||
        tags.as_ref().is_some_and(|items| items.len() > 20 || items.iter().any(|tag| tag.chars().count() > 80)) {
        return Err("Knowledge item exceeds its limit".into());
    }
    if note_blocks.as_ref().is_some_and(|blocks| kind != "note" || !blocks.is_array() ||
        blocks.as_array().is_some_and(|items| items.len() > 500)) {
        return Err("Invalid rich note blocks".into());
    }
    let request = serde_json::json!({"v": 1, "type": "create_knowledge", "projectId": project_id,
        "taskId": task_id,
        "kind": kind, "title": title, "content": content, "url": url,
        "description": description, "tags": tags, "mode": mode,
        "language": language, "responseText": response_text, "noteBlocks": note_blocks, "key": key}).to_string();
    workspace_request(&state, request)
}

fn workspace_request(state: &State<PairingState>, request: String) -> Result<String, String> {
    // The phone closes an oversized line before it can reply. Reject locally so the
    // authenticated session remains usable and the editor can show a normal error.
    if request.len() >= 512 * 1024 { return Err("Request is too large for the phone; reduce this note and retry".into()); }
    let sender = {
        let guard = state.0.lock().map_err(|_| "Pairing state unavailable")?;
        let session = guard.as_ref().ok_or("Connect a phone first")?;
        if !session.status.lock().unwrap().starts_with("Connected") { return Err("Connect a phone first".into()); }
        session.requests.clone()
    };
    let (reply_tx, reply_rx) = mpsc::channel();
    sender.send((request, reply_tx)).map_err(|_| "Phone disconnected")?;
    reply_rx.recv_timeout(std::time::Duration::from_secs(62)).map_err(|_| "Phone did not respond")?
}

#[tauri::command]
fn create_workspace_item(kind: String, owner_type: Option<String>, owner_id: Option<String>, title: String,
    description: Option<String>, key: String, state: State<PairingState>) -> Result<String, String> {
    let action = match kind.as_str() {
        "project" => "create_project",
        "workstream" => "create_workstream",
        "task" => "create_task",
        _ => return Err("Unsupported item type".into()),
    };
    if title.trim().is_empty() || title.chars().count() > 200 || description.as_ref().is_some_and(|d| d.chars().count() > 4096) {
        return Err("Title or description exceeds its limit".into());
    }
    if !is_uuid_key(&key) { return Err("Invalid request key".into()); }
    let request = serde_json::json!({"v": 1, "type": action, "ownerType": owner_type,
        "ownerId": owner_id, "title": title, "description": description, "key": key}).to_string();
    workspace_request(&state, request)
}

#[tauri::command]
fn task_action(action: String, task_id: String, title: Option<String>, preference: Option<String>, key: String,
    state: State<PairingState>) -> Result<String, String> {
    if !matches!(action.as_str(), "rename" | "complete" | "duplicate" | "cancel" | "set_execution") {
        return Err("Unsupported task action".into());
    }
    if task_id.is_empty() || task_id.len() > 128 ||
        !task_id.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-') {
        return Err("Invalid task ID".into());
    }
    if !is_uuid_key(&key) { return Err("Invalid request key".into()); }
    if action == "rename" && !title.as_ref().is_some_and(|t| !t.trim().is_empty() && t.chars().count() <= 200) {
        return Err("Invalid task title".into());
    }
    if action == "set_execution" && !preference.as_deref().is_some_and(|p| matches!(p, "INHERIT" | "HUMAN" | "EXTERNAL")) {
        return Err("Invalid execution preference".into());
    }
    workspace_request(&state, serde_json::json!({"v": 1, "type": "task_action", "action": action,
        "taskId": task_id, "title": title, "preference": preference, "key": key}).to_string())
}

#[tauri::command]
fn reorder_task(task_id: String, after_id: Option<String>, expected_sibling_ids: Vec<String>, key: String,
    state: State<PairingState>) -> Result<String, String> {
    let valid_id = |id: &str| !id.is_empty() && id.len() <= 128 &&
        id.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-');
    if !is_uuid_key(&key) || !valid_id(&task_id) ||
        after_id.as_ref().is_some_and(|id| !valid_id(id)) ||
        !(2..=200).contains(&expected_sibling_ids.len()) ||
        expected_sibling_ids.iter().any(|id| !valid_id(id)) {
        return Err("Invalid task order request".into());
    }
    workspace_request(&state, serde_json::json!({"v": 1, "type": "reorder_task",
        "taskId": task_id, "afterId": after_id, "expectedSiblingIds": expected_sibling_ids,
        "key": key}).to_string())
}

#[tauri::command]
fn focus_preview(task_id: String, state: State<PairingState>) -> Result<String, String> {
    if task_id.is_empty() || task_id.len() > 128 ||
        !task_id.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-') {
        return Err("Invalid task ID".into());
    }
    workspace_request(&state, serde_json::json!({"v": 1, "type": "focus_preview", "taskId": task_id}).to_string())
}

#[tauri::command]
fn focus_start(task_id: String, expected_work_item_id: String, expected_displaced_stream_id: Option<String>,
    key: String, state: State<PairingState>) -> Result<String, String> {
    let valid_id = |id: &str| !id.is_empty() && id.len() <= 128 &&
        id.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-');
    if !valid_id(&task_id) || !valid_id(&expected_work_item_id) ||
        expected_displaced_stream_id.as_deref().is_some_and(|id| !valid_id(id)) || !is_uuid_key(&key) {
        return Err("Invalid focus request".into());
    }
    workspace_request(&state, serde_json::json!({"v": 1, "type": "focus_start", "taskId": task_id,
        "expectedWorkItemId": expected_work_item_id, "expectedDisplacedStreamId": expected_displaced_stream_id,
        "key": key}).to_string())
}

#[tauri::command]
fn preview_workspace_bulk(target: String, owner_type: Option<String>, owner_id: Option<String>,
    source: String, key: String, state: State<PairingState>) -> Result<String, String> {
    if source.chars().count() > 250_000 || !is_uuid_key(&key) { return Err("Import is too large or request key is invalid".into()); }
    let request = serde_json::json!({"v": 1, "type": "preview_bulk", "target": target,
        "ownerType": owner_type, "ownerId": owner_id, "source": source, "key": key}).to_string();
    workspace_request(&state, request)
}

#[tauri::command]
fn apply_workspace_bulk(key: String, state: State<PairingState>) -> Result<String, String> {
    if !is_uuid_key(&key) { return Err("Invalid request key".into()); }
    workspace_request(&state, serde_json::json!({"v": 1, "type": "apply_bulk", "key": key}).to_string())
}

fn is_uuid_key(key: &str) -> bool {
    key.len() == 36 && key.chars().enumerate().all(|(i, c)|
        if [8, 13, 18, 23].contains(&i) { c == '-' } else { c.is_ascii_hexdigit() })
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

fn offline_base(app: &tauri::AppHandle) -> Result<std::path::PathBuf, String> {
    app.path().app_local_data_dir().map_err(|e| format!("Offline storage unavailable: {e}"))
}

#[tauri::command]
fn offline_copy_list(app: tauri::AppHandle) -> Result<Vec<offline_copy::CopySummary>, String> {
    offline_copy::list(&offline_base(&app)?)
}

#[tauri::command]
async fn offline_copy_capture(app: tauri::AppHandle) -> Result<offline_copy::CopySummary, String> {
    tauri::async_runtime::spawn_blocking(move || {
        let base = offline_base(&app)?;
        let state = app.state::<PairingState>();
        offline_copy::capture(&base, &mut |request| workspace_request(&state, request))
    }).await.map_err(|error| format!("Offline copy interrupted: {error}"))?
}

#[tauri::command]
fn offline_copy_read(app: tauri::AppHandle, device_id: String, kind: String,
    project_id: Option<String>, item_id: Option<String>) -> Result<String, String> {
    offline_copy::read_data(&offline_base(&app)?, &device_id, &kind, project_id.as_deref(), item_id.as_deref())
}

#[tauri::command]
fn offline_copy_media_chunk(app: tauri::AppHandle, device_id: String, kind: String,
    project_id: String, item_id: String, media_id: String, offset: usize) -> Result<String, String> {
    offline_copy::media_chunk(&offline_base(&app)?, &device_id, &kind, &project_id, &item_id, &media_id, offset)
}

fn main() {
    tauri::Builder::default()
        .manage(PairingState::default())
        .invoke_handler(tauri::generate_handler![list_lan_addresses, start_pairing, pairing_status, project_tree, map_appearance, set_map_appearance, map_place, map_delete, map_rename, project_knowledge, knowledge_capabilities, knowledge_item, voice_chunk, upload_note_image, note_image_chunk, save_knowledge_note, save_knowledge_prompt, save_knowledge_link, create_knowledge, create_workspace_item, task_action, reorder_task, focus_preview, focus_start, preview_workspace_bulk, apply_workspace_bulk, cancel_pairing, offline_copy_list, offline_copy_capture, offline_copy_read, offline_copy_media_chunk])
        .run(tauri::generate_context!())
        .expect("Virlin Connect failed to start");
}

#[cfg(test)]
mod device_tests {
    use super::is_usb_tether_description;

    #[test]
    fn workspace_frames_are_bounded_and_line_delimited() {
        assert_eq!(super::read_bounded_line(&mut &b"one\ntwo\n"[..], 8).unwrap(), "one");
        assert!(super::read_bounded_line(&mut &b"toolong\n"[..], 3).is_err());
        assert!(super::read_bounded_line(&mut &b"unfinished"[..], 32).is_err());
    }

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

}
