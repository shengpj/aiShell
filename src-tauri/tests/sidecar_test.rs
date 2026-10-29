use ai_shell_lib::sidecar::Sidecar;
use std::path::Path;
use std::time::Duration;

#[test]
#[ignore = "requires a packaged backend jar and Java on PATH"]
fn spawns_backend_and_enforces_token() {
    let jar = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../backend/target/backend-0.0.1-SNAPSHOT.jar");
    assert!(jar.is_file(), "backend jar missing at {}", jar.display());

    let temp = tempfile::tempdir().expect("temp dir");
    let sidecar = Sidecar::spawn(
        &jar,
        Path::new("java"),
        &temp.path().join("backend.log"),
        &temp.path().join("data"),
    )
    .expect("spawn backend");

    let health = sidecar
        .wait_ready(Duration::from_secs(90))
        .expect("backend ready");
    assert_eq!(health.status, "UP");
    assert!(!health.model.is_empty());

    let ticket = sidecar.ticket("terminal").expect("issue ticket");
    assert!(!ticket.ticket.is_empty());
    assert!(!ticket.expires_at.is_empty());

    let unauthorized = reqwest::blocking::get(format!("{}/api/health", sidecar.base_url()))
        .expect("unauthorized request");
    assert_eq!(unauthorized.status(), 401);

    sidecar.stop();
}
