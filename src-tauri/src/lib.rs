mod credentials;
pub mod sidecar;

use serde::Serialize;
use sidecar::{HealthResponse, Sidecar, TicketResponse};
use std::path::PathBuf;
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::Duration;
use tauri::Manager;

#[derive(Clone, Copy, Serialize)]
#[serde(rename_all = "camelCase")]
pub enum BackendStatus {
    Starting,
    Ready,
    Failed,
}

struct StatusInner {
    status: BackendStatus,
    base_url: String,
    pid: Option<u32>,
    error: Option<String>,
}

pub struct BackendState {
    status: Mutex<StatusInner>,
    sidecar: Mutex<Option<Arc<Sidecar>>>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct BackendInfo {
    status: BackendStatus,
    base_url: String,
    pid: Option<u32>,
    error: Option<String>,
}

impl BackendState {
    fn start(app: &tauri::AppHandle) -> Self {
        let state = Self {
            status: Mutex::new(StatusInner {
                status: BackendStatus::Starting,
                base_url: String::new(),
                pid: None,
                error: None,
            }),
            sidecar: Mutex::new(None),
        };

        let jar = match resolve_jar(app) {
            Ok(jar) => jar,
            Err(error) => {
                state.fail(error);
                return state;
            }
        };
        let java = resolve_java(app);
        let log = log_path(app);

        match Sidecar::spawn(&jar, &java, &log, &data_dir(app)) {
            Ok(sidecar) => {
                let sidecar = Arc::new(sidecar);
                {
                    let mut status = lock(&state.status);
                    status.base_url = sidecar.base_url().to_string();
                    status.pid = Some(sidecar.pid());
                }
                *lock(&state.sidecar) = Some(Arc::clone(&sidecar));
                spawn_readiness_probe(app.clone(), sidecar, log);
            }
            Err(error) => state.fail(format!(
                "failed to start backend: {error} (jar: {})",
                jar.display()
            )),
        }

        state
    }

    fn fail(&self, error: String) {
        let mut status = lock(&self.status);
        status.status = BackendStatus::Failed;
        status.error = Some(error);
    }

    fn stop(&self) {
        if let Some(sidecar) = lock(&self.sidecar).take() {
            sidecar.stop();
        }
    }
}

fn spawn_readiness_probe(
    app: tauri::AppHandle,
    sidecar: Arc<Sidecar>,
    log: PathBuf,
) {
    std::thread::spawn(move || match sidecar.wait_ready(Duration::from_secs(90)) {
        Ok(_) => {
            let state = app.state::<BackendState>();
            let mut status = lock(&state.status);
            status.status = BackendStatus::Ready;
            status.error = None;
        }
        Err(error) => {
            {
                let state = app.state::<BackendState>();
                let mut status = lock(&state.status);
                status.status = BackendStatus::Failed;
                status.error = Some(format!("{error} (log: {})", log.display()));
            }
            sidecar.stop();
        }
    });
}

fn resolve_jar(app: &tauri::AppHandle) -> Result<PathBuf, String> {
    if let Some(path) = std::env::var_os("AI_SHELL_BACKEND_JAR") {
        let path = PathBuf::from(path);
        return if path.is_file() {
            Ok(path)
        } else {
            Err(format!(
                "AI_SHELL_BACKEND_JAR does not point to a file: {}",
                path.display()
            ))
        };
    }

    let bundled = app
        .path()
        .resource_dir()
        .ok()
        .map(|resources| resources.join("backend/backend.jar"))
        .filter(|path| path.is_file());
    let dev = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../backend/target/backend-0.0.1-SNAPSHOT.jar");

    // A release build is the thing users run, so the jar next to the executable wins. The dev path
    // is absolute and baked in at compile time, which means it also exists on the build machine —
    // preferring it there would silently run the wrong jar from a packaged app.
    if !cfg!(debug_assertions) {
        if let Some(path) = bundled.as_ref() {
            return Ok(plain_path(path.clone()));
        }
    }
    if dev.is_file() {
        return Ok(dev);
    }
    if let Some(path) = bundled {
        return Ok(plain_path(path));
    }

    Err("backend jar not found; run scripts/package.ps1 or set AI_SHELL_BACKEND_JAR"
        .to_string())
}

/// Tauri returns resource paths in the Windows verbatim form (`\\?\E:\...`). Passing that to
/// `java -jar` makes the JVM fail to open the jar, so strip the prefix before spawning.
fn plain_path(path: PathBuf) -> PathBuf {
    #[cfg(windows)]
    {
        let text = path.as_os_str().to_string_lossy();
        if let Some(rest) = text.strip_prefix(r"\\?\UNC\") {
            return PathBuf::from(format!(r"\\{rest}"));
        }
        if let Some(rest) = text.strip_prefix(r"\\?\") {
            return PathBuf::from(rest);
        }
    }
    path
}

fn resolve_java(app: &tauri::AppHandle) -> PathBuf {
    if let Some(path) = std::env::var_os("AI_SHELL_JAVA") {
        return PathBuf::from(path);
    }
    if let Ok(resources) = app.path().resource_dir() {
        let bundled = resources
            .join("backend/runtime/bin")
            .join(if cfg!(windows) { "java.exe" } else { "java" });
        if bundled.is_file() {
            return plain_path(bundled);
        }
    }
    PathBuf::from("java")
}

fn log_path(app: &tauri::AppHandle) -> PathBuf {
    app.path()
        .app_log_dir()
        .map(|dir| dir.join("backend.log"))
        .unwrap_or_else(|_| std::env::temp_dir().join("ai-shell-backend.log"))
}

fn data_dir(app: &tauri::AppHandle) -> PathBuf {
    app.path()
        .app_data_dir()
        .unwrap_or_else(|_| std::env::temp_dir().join("ai-shell-data"))
}

fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

#[tauri::command]
fn backend_info(state: tauri::State<'_, BackendState>) -> BackendInfo {
    let status = lock(&state.status);
    BackendInfo {
        status: status.status,
        base_url: status.base_url.clone(),
        pid: status.pid,
        error: status.error.clone(),
    }
}

#[tauri::command]
async fn backend_health(state: tauri::State<'_, BackendState>) -> Result<HealthResponse, String> {
    let sidecar = lock(&state.sidecar)
        .clone()
        .ok_or_else(|| "backend is not running".to_string())?;
    tauri::async_runtime::spawn_blocking(move || sidecar.health())
        .await
        .map_err(|error| error.to_string())?
}

#[tauri::command]
async fn backend_ticket(
    state: tauri::State<'_, BackendState>,
    purpose: String,
) -> Result<TicketResponse, String> {
    let sidecar = lock(&state.sidecar)
        .clone()
        .ok_or_else(|| "backend is not running".to_string())?;
    tauri::async_runtime::spawn_blocking(move || sidecar.ticket(&purpose))
        .await
        .map_err(|error| error.to_string())?
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let app = tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .setup(|app| {
            let state = BackendState::start(app.handle());
            app.manage(state);
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            backend_info,
            backend_health,
            backend_ticket
        ])
        .build(tauri::generate_context!())
        .expect("failed to build AI Shell");

    app.run(|app_handle, event| {
        if let tauri::RunEvent::Exit = event {
            app_handle.state::<BackendState>().stop();
        }
    });
}

#[cfg(all(test, windows))]
mod tests {
    use super::plain_path;
    use std::path::PathBuf;

    #[test]
    fn strips_the_verbatim_prefix_before_handing_paths_to_java() {
        assert_eq!(
            plain_path(PathBuf::from(r"\\?\E:\app\backend\backend.jar")),
            PathBuf::from(r"E:\app\backend\backend.jar")
        );
        assert_eq!(
            plain_path(PathBuf::from(r"\\?\UNC\server\share\a.jar")),
            PathBuf::from(r"\\server\share\a.jar")
        );
        assert_eq!(
            plain_path(PathBuf::from(r"E:\app\a.jar")),
            PathBuf::from(r"E:\app\a.jar")
        );
    }
}
