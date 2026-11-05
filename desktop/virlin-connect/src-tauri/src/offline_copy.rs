use base64::{engine::general_purpose::STANDARD, Engine as _};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{collections::{HashMap, HashSet}, fs, io::{Read, Seek, SeekFrom}, path::{Path, PathBuf}, time::{SystemTime, UNIX_EPOCH}};

const MAX_COPY_BYTES: usize = 256 * 1024 * 1024;
const MAX_ITEMS: usize = 5000;

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct OfflineCopy {
    pub version: u8,
    pub device_id: String,
    pub device_name: String,
    pub captured_at: u64,
    pub tree: Value,
    pub knowledge: HashMap<String, Value>,
    pub items: HashMap<String, Value>,
    pub images: HashMap<String, String>,
    pub audio: HashMap<String, String>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CopySummary {
    device_id: String,
    device_name: String,
    captured_at: u64,
    projects: usize,
}

fn valid_id(value: &str) -> bool {
    !value.is_empty() && value.len() <= 128 &&
        value.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-')
}
fn valid_device_id(value: &str) -> bool {
    value.len() == 36 && value.chars().enumerate().all(|(index, character)|
        if [8, 13, 18, 23].contains(&index) { character == '-' } else { character.is_ascii_hexdigit() })
}
fn root(base: &Path) -> PathBuf { base.join("offline-copies") }
fn copy_dir(base: &Path, device_id: &str) -> Result<PathBuf, String> {
    if !valid_device_id(device_id) { return Err("Invalid phone identity".into()); }
    Ok(root(base).join(device_id))
}
fn read_copy(base: &Path, device_id: &str) -> Result<OfflineCopy, String> {
    let dir = copy_dir(base, device_id)?;
    let manifest = dir.join("manifest.json");
    let fallback = dir.join("manifest.previous");
    let bytes = fs::read(if manifest.exists() { manifest } else { fallback })
        .map_err(|_| "No offline copy for this phone".to_string())?;
    let copy: OfflineCopy = serde_json::from_slice(&bytes).map_err(|_| "Offline copy is damaged".to_string())?;
    if copy.version != 1 || copy.device_id != device_id { return Err("Offline copy identity mismatch".into()); }
    Ok(copy)
}
pub fn list(base: &Path) -> Result<Vec<CopySummary>, String> {
    let folder = root(base);
    if !folder.exists() { return Ok(Vec::new()); }
    let mut copies = Vec::new();
    for entry in fs::read_dir(folder).map_err(|e| e.to_string())? {
        let entry = entry.map_err(|e| e.to_string())?;
        let name = entry.file_name().to_string_lossy().into_owned();
        if !valid_device_id(&name) { continue; }
        if let Ok(copy) = read_copy(base, &name) {
            copies.push(CopySummary { device_id: copy.device_id, device_name: copy.device_name,
                captured_at: copy.captured_at, projects: copy.tree["projects"].as_array().map_or(0, Vec::len) });
        }
    }
    copies.sort_by(|a, b| b.captured_at.cmp(&a.captured_at));
    Ok(copies)
}
fn checked_response(raw: String, expected_type: &str) -> Result<Value, String> {
    let value: Value = serde_json::from_str(&raw).map_err(|_| "Invalid phone response")?;
    if value["v"] != 1 || value["type"] != expected_type {
        return Err(format!("Phone could not provide {expected_type}: {}", value["code"].as_str().unwrap_or("unexpected response")));
    }
    Ok(value)
}
fn media_key(project_id: &str, item_id: &str, media_id: &str) -> String {
    format!("{project_id}/{item_id}/{media_id}")
}
fn fetch_media<F>(request: &mut F, kind: &str, project_id: &str, item_id: &str,
    media_id: &str, limit: usize, total_budget: &mut usize) -> Result<Vec<u8>, String>
where F: FnMut(String) -> Result<String, String> {
    let (request_type, response_type, id_key) = if kind == "image" {
        ("read_note_image_chunk", "note_image_chunk", "imagePath")
    } else { ("read_voice_chunk", "voice_chunk", "clipId") };
    let mut bytes = Vec::new();
    let mut expected_total = None;
    loop {
        let raw = request(json!({"v":1,"type":request_type,"projectId":project_id,
            "captureId":item_id,(id_key):media_id,"offset":bytes.len()}).to_string())?;
        let chunk = checked_response(raw, response_type)?;
        let total = chunk["totalBytes"].as_u64().ok_or("Missing media size")? as usize;
        if total == 0 || total > limit || expected_total.is_some_and(|old| old != total) ||
            chunk["offset"].as_u64() != Some(bytes.len() as u64) ||
            chunk[id_key].as_str() != Some(media_id) {
            return Err("Media size or identity changed during copy".into());
        }
        expected_total = Some(total);
        if *total_budget + total > MAX_COPY_BYTES { return Err("Offline copy exceeds the 256 MB media limit".into()); }
        let encoded = chunk["data"].as_str().ok_or("Missing media data")?;
        let decoded = STANDARD.decode(encoded).map_err(|_| "Invalid media encoding")?;
        if decoded.is_empty() || bytes.len() + decoded.len() > total { return Err("Invalid media chunk size".into()); }
        bytes.extend(decoded);
        if chunk["done"] == true {
            if bytes.len() != total { return Err("Media ended early".into()); }
            *total_budget += total;
            return Ok(bytes);
        }
        if bytes.len() == total { return Err("Media completion not confirmed".into()); }
    }
}
fn save_asset(dir: &Path, bytes: &[u8]) -> Result<String, String> {
    let hash = Sha256::digest(bytes).iter().map(|b| format!("{b:02x}")).collect::<String>();
    let path = dir.join(format!("{hash}.bin"));
    if !path.exists() { fs::write(&path, bytes).map_err(|e| format!("Could not save media: {e}"))?; }
    Ok(hash)
}
pub fn capture<F>(base: &Path, request: &mut F) -> Result<CopySummary, String>
where F: FnMut(String) -> Result<String, String> {
    let tree = checked_response(request(json!({"v":1,"type":"get_tree"}).to_string())?, "tree")?;
    let device_id = tree["deviceId"].as_str().ok_or("Update Virlin on the phone before creating an offline copy")?.to_string();
    if !valid_device_id(&device_id) { return Err("Phone did not provide a stable identity".into()); }
    let device_name = tree["deviceName"].as_str().unwrap_or("Android phone").chars().take(80).collect::<String>();
    let projects = tree["projects"].as_array().ok_or("Invalid project list")?.clone();
    if projects.len() > MAX_ITEMS { return Err("Too many projects for an offline copy".into()); }
    let dir = copy_dir(base, &device_id)?;
    let asset_dir = dir.join("assets");
    fs::create_dir_all(&asset_dir).map_err(|e| format!("Could not prepare offline folder: {e}"))?;
    let mut copy = OfflineCopy { version: 1, device_id: device_id.clone(), device_name,
        captured_at: SystemTime::now().duration_since(UNIX_EPOCH).map_err(|e| e.to_string())?.as_secs(),
        tree, knowledge: HashMap::new(), items: HashMap::new(), images: HashMap::new(), audio: HashMap::new() };
    let mut item_count = 0;
    let mut media_bytes = 0;
    for project in &projects {
        let project_id = project["id"].as_str().filter(|id| valid_id(id)).ok_or("Invalid project ID")?;
        let list = checked_response(request(json!({"v":1,"type":"get_knowledge","projectId":project_id}).to_string())?, "knowledge")?;
        if list["projectId"].as_str() != Some(project_id) { return Err("Knowledge belongs to another project".into()); }
        let items = list["items"].as_array().ok_or("Invalid knowledge list")?;
        item_count += items.len();
        if item_count > MAX_ITEMS { return Err("Too many knowledge items for an offline copy".into()); }
        for item in items {
            let item_id = item["id"].as_str().filter(|id| valid_id(id)).ok_or("Invalid knowledge item ID")?;
            let detail = checked_response(request(json!({"v":1,"type":"get_knowledge_item",
                "projectId":project_id,"captureId":item_id}).to_string())?, "knowledge_item")?;
            if detail["projectId"].as_str() != Some(project_id) || detail["id"].as_str() != Some(item_id) {
                return Err("Knowledge item changed identity during copy".into());
            }
            if detail["updatedAt"] != item["updatedAt"] {
                return Err("Knowledge changed during copy; reconnect and try again".into());
            }
            if let Some(blocks) = detail["noteBlocks"].as_array() {
                for block in blocks {
                    if block["type"] != "IMAGE" { continue; }
                    if let Some(image_path) = block["imagePath"].as_str() {
                        let key = media_key(project_id, item_id, image_path);
                        if !copy.images.contains_key(&key) {
                            let bytes = fetch_media(request, "image", project_id, item_id, image_path,
                                25 * 1024 * 1024, &mut media_bytes)?;
                            copy.images.insert(key, save_asset(&asset_dir, &bytes)?);
                        }
                    }
                }
            }
            if let Some(clips) = detail["voiceClips"].as_array() {
                for clip in clips {
                    let clip_id = clip["id"].as_str().filter(|id| valid_id(id)).ok_or("Invalid voice clip ID")?;
                    let bytes = fetch_media(request, "audio", project_id, item_id, clip_id,
                        20 * 1024 * 1024, &mut media_bytes)?;
                    copy.audio.insert(media_key(project_id, item_id, clip_id), save_asset(&asset_dir, &bytes)?);
                }
            }
            copy.items.insert(media_key(project_id, item_id, ""), detail);
        }
        copy.knowledge.insert(project_id.to_string(), list);
    }
    let manifest = serde_json::to_vec(&copy).map_err(|e| e.to_string())?;
    if manifest.len() > 64 * 1024 * 1024 { return Err("Offline copy metadata is too large".into()); }
    let pending = dir.join("manifest.pending");
    let current = dir.join("manifest.json");
    let backup = dir.join("manifest.previous");
    if !current.exists() && backup.exists() {
        fs::rename(&backup, &current).map_err(|e| format!("Could not recover previous offline copy: {e}"))?;
    }
    fs::write(&pending, manifest).map_err(|e| format!("Could not write offline copy: {e}"))?;
    if backup.exists() { fs::remove_file(&backup).map_err(|e| e.to_string())?; }
    if current.exists() { fs::rename(&current, &backup).map_err(|e| e.to_string())?; }
    if let Err(error) = fs::rename(&pending, &current) {
        if backup.exists() { let _ = fs::rename(&backup, &current); }
        return Err(format!("Could not replace offline copy: {error}"));
    }
    if backup.exists() { let _ = fs::remove_file(&backup); }
    let used: HashSet<_> = copy.images.values().chain(copy.audio.values()).cloned().collect();
    if let Ok(entries) = fs::read_dir(&asset_dir) {
        for entry in entries.flatten() {
            let name = entry.file_name().to_string_lossy().into_owned();
            if name.len() == 68 && name.ends_with(".bin") && name[..64].bytes().all(|b| b.is_ascii_hexdigit())
                && !used.contains(&name[..64]) { let _ = fs::remove_file(entry.path()); }
        }
    }
    Ok(CopySummary { device_id, device_name: copy.device_name, captured_at: copy.captured_at, projects: projects.len() })
}
pub fn read_data(base: &Path, device_id: &str, kind: &str, project_id: Option<&str>, item_id: Option<&str>) -> Result<String, String> {
    let copy = read_copy(base, device_id)?;
    let value = match kind {
        "tree" => &copy.tree,
        "knowledge" => copy.knowledge.get(project_id.ok_or("Missing project ID")?).ok_or("Project not in offline copy")?,
        "item" => copy.items.get(&media_key(project_id.ok_or("Missing project ID")?,
            item_id.ok_or("Missing item ID")?, "")).ok_or("Item not in offline copy")?,
        _ => return Err("Invalid offline read".into()),
    };
    Ok(value.to_string())
}
pub fn media_chunk(base: &Path, device_id: &str, kind: &str, project_id: &str, item_id: &str,
    media_id: &str, offset: usize) -> Result<String, String> {
    let copy = read_copy(base, device_id)?;
    let hash = match kind {
        "image" => copy.images.get(&media_key(project_id, item_id, media_id)),
        "audio" => copy.audio.get(&media_key(project_id, item_id, media_id)),
        _ => return Err("Invalid media type".into()),
    }.ok_or("Media not in offline copy")?;
    let mut file = fs::File::open(copy_dir(base, device_id)?.join("assets").join(format!("{hash}.bin")))
        .map_err(|_| "Offline media is missing")?;
    let total = file.metadata().map_err(|e| e.to_string())?.len() as usize;
    if offset >= total || total > 25 * 1024 * 1024 { return Err("Invalid media offset".into()); }
    let end = (offset + 48 * 1024).min(total);
    file.seek(SeekFrom::Start(offset as u64)).map_err(|e| e.to_string())?;
    let mut bytes = vec![0; end - offset];
    file.read_exact(&mut bytes).map_err(|e| e.to_string())?;
    let response = if kind == "image" { json!({"v":1,"type":"note_image_chunk","imagePath":media_id,
        "offset":offset,"totalBytes":total,"data":STANDARD.encode(&bytes),"done":end==total}) }
    else { json!({"v":1,"type":"voice_chunk","clipId":media_id,
        "offset":offset,"totalBytes":total,"data":STANDARD.encode(&bytes),"done":end==total}) };
    Ok(response.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    fn temp_base() -> PathBuf { std::env::temp_dir().join(format!("virlin-offline-test-{}-{}",
        std::process::id(), SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_nanos())) }
    #[test]
    fn phone_identity_cannot_escape_the_copy_folder() {
        assert!(valid_device_id("6d49f6a2-98f1-4b69-a831-70ab02d03534"));
        assert!(!valid_device_id("../other"));
        assert!(!valid_device_id("6d49f6a2-98f1-4b69-a831-70ab02d03534/"));
    }
    #[test]
    fn failed_refresh_keeps_the_previous_read_only_copy() {
        let base = temp_base();
        let device = "6d49f6a2-98f1-4b69-a831-70ab02d03534";
        let mut first = |raw: String| -> Result<String, String> {
            let value: Value = serde_json::from_str(&raw).unwrap();
            Ok(match value["type"].as_str().unwrap() {
                "get_tree" => json!({"v":1,"type":"tree","deviceId":device,"deviceName":"Test phone",
                    "projects":[{"id":"project-1","name":"Original"}]}).to_string(),
                "get_knowledge" => json!({"v":1,"type":"knowledge","projectId":"project-1","items":[]}).to_string(),
                other => panic!("Unexpected request: {other}"),
            })
        };
        assert!(capture(&base, &mut first).is_ok());
        let original = read_data(&base, device, "tree", None, None).unwrap();
        let mut failing = |raw: String| -> Result<String, String> {
            let value: Value = serde_json::from_str(&raw).unwrap();
            if value["type"] == "get_tree" {
                Ok(json!({"v":1,"type":"tree","deviceId":device,"deviceName":"Test phone",
                    "projects":[{"id":"project-1","name":"Changed"}]}).to_string())
            } else { Err("Phone disconnected during refresh".into()) }
        };
        assert!(capture(&base, &mut failing).is_err());
        assert_eq!(read_data(&base, device, "tree", None, None).unwrap(), original);
        assert_eq!(list(&base).unwrap().len(), 1);
        fs::remove_dir_all(&base).unwrap();
    }
}
