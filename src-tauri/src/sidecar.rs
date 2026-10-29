use serde::{Deserialize, Serialize};
use std::io;
use std::net::TcpListener;
use std::path::Path;
use std::process::{Child, Command, Stdio};
use std::sync::Mutex;
use std::time::{Duration, Instant};

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct HealthResponse {
    pub status: String,
    pub model: String,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct TicketResponse {
    pub ticket: String,
    pub expires_at: String,
}

pub struct Sidecar {
    base_url: String,
    token: String,
    pid: u32,
    child: Mutex<Option<Child>>,
    #[cfg(windows)]
    _job: Option<JobGuard>,
}

#[cfg(windows)]
struct JobGuard {
    _handle: std::os::windows::io::OwnedHandle,
}

impl Sidecar {
    pub fn spawn(jar: &Path, java: &Path, log_path: &Path, data_dir: &Path) -> io::Result<Self> {
        let token = uuid::Uuid::new_v4().simple().to_string();
        let port = free_port()?;

        if let Some(parent) = log_path.parent() {
            std::fs::create_dir_all(parent)?;
        }
        std::fs::create_dir_all(data_dir)?;
        let stdout = std::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(log_path)?;
        let stderr = stdout.try_clone()?;

        let mut command = Command::new(java);
        command
            .arg("-jar")
            .arg(jar)
            .arg(format!("--server.port={port}"))
            .env("AI_SHELL_TOKEN", &token)
            .env("AI_SHELL_DATA_DIR", data_dir)
            .stdin(Stdio::null())
            .stdout(Stdio::from(stdout))
            .stderr(Stdio::from(stderr));

        if let Some(master_key) = crate::credentials::master_key_base64() {
            command.env("AI_SHELL_MASTER_KEY", master_key);
        }

        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            const CREATE_NO_WINDOW: u32 = 0x0800_0000;
            command.creation_flags(CREATE_NO_WINDOW);
        }

        let child = command.spawn()?;
        #[cfg(windows)]
        let job = assign_to_job(&child)
            .ok()
            .map(|handle| JobGuard { _handle: handle });

        Ok(Self {
            base_url: format!("http://127.0.0.1:{port}"),
            token,
            pid: child.id(),
            child: Mutex::new(Some(child)),
            #[cfg(windows)]
            _job: job,
        })
    }

    pub fn base_url(&self) -> &str {
        &self.base_url
    }

    pub fn pid(&self) -> u32 {
        self.pid
    }

    pub fn wait_ready(&self, timeout: Duration) -> Result<HealthResponse, String> {
        let client = self.client()?;
        let url = format!("{}/api/health", self.base_url);
        let deadline = Instant::now() + timeout;
        let mut last = String::from("no response yet");

        while Instant::now() < deadline {
            if let Some(status) = self.exit_status() {
                return Err(format!("backend exited early ({status})"));
            }
            match client.get(&url).bearer_auth(&self.token).send() {
                Ok(response) if response.status().is_success() => {
                    return response
                        .json::<HealthResponse>()
                        .map_err(|error| format!("invalid health response: {error}"));
                }
                Ok(response) => last = format!("HTTP {}", response.status()),
                Err(error) => last = error.to_string(),
            }
            std::thread::sleep(Duration::from_millis(250));
        }

        Err(format!(
            "backend did not become ready within {timeout:?}: {last}"
        ))
    }

    pub fn health(&self) -> Result<HealthResponse, String> {
        let response = self
            .client()?
            .get(format!("{}/api/health", self.base_url))
            .bearer_auth(&self.token)
            .send()
            .map_err(|error| error.to_string())?;

        if !response.status().is_success() {
            return Err(format!("health check failed: HTTP {}", response.status()));
        }
        response
            .json::<HealthResponse>()
            .map_err(|error| format!("invalid health response: {error}"))
    }

    pub fn ticket(&self, purpose: &str) -> Result<TicketResponse, String> {
        let response = self
            .client()?
            .post(format!("{}/api/tickets", self.base_url))
            .bearer_auth(&self.token)
            .json(&serde_json::json!({ "purpose": purpose }))
            .send()
            .map_err(|error| error.to_string())?;

        if !response.status().is_success() {
            return Err(format!(
                "ticket request failed: HTTP {}",
                response.status()
            ));
        }
        response
            .json::<TicketResponse>()
            .map_err(|error| format!("invalid ticket response: {error}"))
    }

    pub fn stop(&self) {
        let mut guard = lock(&self.child);
        if let Some(mut child) = guard.take() {
            let _ = child.kill();
            let _ = child.wait();
        }
    }

    fn client(&self) -> Result<reqwest::blocking::Client, String> {
        reqwest::blocking::Client::builder()
            .timeout(Duration::from_secs(3))
            .build()
            .map_err(|error| error.to_string())
    }

    fn exit_status(&self) -> Option<std::process::ExitStatus> {
        let mut guard = lock(&self.child);
        guard.as_mut().and_then(|child| child.try_wait().ok().flatten())
    }
}

impl Drop for Sidecar {
    fn drop(&mut self) {
        self.stop();
    }
}

fn free_port() -> io::Result<u16> {
    let listener = TcpListener::bind(("127.0.0.1", 0))?;
    let port = listener.local_addr()?.port();
    drop(listener);
    Ok(port)
}

#[cfg(windows)]
fn assign_to_job(child: &Child) -> io::Result<std::os::windows::io::OwnedHandle> {
    use std::os::windows::io::{AsRawHandle, FromRawHandle, OwnedHandle};
    use windows::core::PCWSTR;
    use windows::Win32::Foundation::HANDLE;
    use windows::Win32::System::JobObjects::{
        AssignProcessToJobObject, CreateJobObjectW, JobObjectExtendedLimitInformation,
        SetInformationJobObject, JOBOBJECT_EXTENDED_LIMIT_INFORMATION,
        JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE,
    };

    unsafe {
        let job = CreateJobObjectW(None, PCWSTR::null())
            .map_err(|error| io::Error::other(error.to_string()))?;
        let job = OwnedHandle::from_raw_handle(job.0 as _);
        let mut info = JOBOBJECT_EXTENDED_LIMIT_INFORMATION::default();
        info.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
        SetInformationJobObject(
            HANDLE(job.as_raw_handle()),
            JobObjectExtendedLimitInformation,
            &info as *const _ as *const core::ffi::c_void,
            std::mem::size_of::<JOBOBJECT_EXTENDED_LIMIT_INFORMATION>() as u32,
        )
        .map_err(|error| io::Error::other(error.to_string()))?;
        AssignProcessToJobObject(HANDLE(job.as_raw_handle()), HANDLE(child.as_raw_handle()))
            .map_err(|error| io::Error::other(error.to_string()))?;
        Ok(job)
    }
}

fn lock<T>(mutex: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    mutex
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}
