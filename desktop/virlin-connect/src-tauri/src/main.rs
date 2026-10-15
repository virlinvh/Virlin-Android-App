#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

#[allow(dead_code)] // Staged behind the in-app scanner and explicit phone confirmation.
mod pairing;

use serde::Serialize;
use std::path::PathBuf;
use std::process::{Command, Stdio};
use tauri::{Manager, path::BaseDirectory};

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
    Ok(devices)
}

#[tauri::command]
fn open_virlin(app: tauri::AppHandle, serial: String) -> Result<(), String> {
    // A serial supplied by the UI is never passed to a shell, but still must match
    // a currently authorized physical device. This also prevents stale selections.
    let devices = list_devices(app.clone())?;
    let device = devices.iter().find(|d| d.serial == serial && d.state == "device" && d.kind != "Emulator")
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
        .invoke_handler(tauri::generate_handler![list_devices, open_virlin])
        .run(tauri::generate_context!())
        .expect("Virlin Connect failed to start");
}
