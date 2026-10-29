param(
  [switch]$SkipTests,
  [string]$Bundle = ""
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$backend = Join-Path $root "backend"
$target = Join-Path $backend "target"
$jar = Join-Path $target "backend-0.0.1-SNAPSHOT.jar"
$runtime = Join-Path $target "runtime"
$exe = Join-Path $root "src-tauri/target/release/ai-shell.exe"
$version = (Get-Content (Join-Path $root "package.json") -Raw | ConvertFrom-Json).version
$out = Join-Path $root "release/AI-Shell-$version-win-x64"
$zip = Join-Path $root "release/AI-Shell-$version-win-x64.zip"

$modules = @(
  "java.base", "java.compiler", "java.desktop", "java.instrument",
  "java.logging", "java.management", "java.naming", "java.net.http",
  "java.prefs", "java.rmi", "java.scripting", "java.security.jgss",
  "java.security.sasl", "java.sql", "java.transaction.xa", "java.xml",
  "jdk.crypto.ec", "jdk.crypto.cryptoki", "jdk.management",
  "jdk.naming.dns", "jdk.unsupported", "jdk.zipfs"
) -join ","

Write-Host "==> building backend"
Push-Location $backend
try {
  $mvnArgs = @("-o", "-q")
  if ($SkipTests) {
    $mvnArgs += "-DskipTests"
  }
  $mvnArgs += "package"
  & mvn @mvnArgs
  if ($LASTEXITCODE -ne 0) {
    throw "mvn package failed with exit code $LASTEXITCODE"
  }
} finally {
  Pop-Location
}
if (-not (Test-Path $jar)) {
  throw "backend jar missing: $jar"
}

Write-Host "==> building runtime image with jlink"
if (-not (Get-Command jlink -ErrorAction SilentlyContinue)) {
  throw "jlink was not found on PATH; install a JDK 21"
}
if (Test-Path $runtime) {
  Remove-Item -Recurse -Force $runtime
}
& jlink --add-modules $modules --strip-debug --no-header-files --no-man-pages `
  --compress=zip-6 --output $runtime
if ($LASTEXITCODE -ne 0) {
  throw "jlink failed with exit code $LASTEXITCODE"
}
$java = Join-Path $runtime "bin/java.exe"
if (-not (Test-Path $java)) {
  throw "jlink did not produce $java"
}

Write-Host "==> building the desktop app"
Push-Location $root
try {
  $tauriArgs = @("build")
  if ($Bundle) {
    $tauriArgs += @("--bundles", $Bundle)
  } else {
    $tauriArgs += "--no-bundle"
  }
  & npm run tauri -- @tauriArgs
  if ($LASTEXITCODE -ne 0) {
    throw "tauri build failed with exit code $LASTEXITCODE"
  }
} finally {
  Pop-Location
}
if (-not (Test-Path $exe)) {
  throw "tauri build did not produce $exe"
}

Write-Host "==> assembling portable package"
if (Test-Path $out) {
  Remove-Item -Recurse -Force $out
}
New-Item -ItemType Directory -Path (Join-Path $out "backend") -Force | Out-Null
Copy-Item $exe $out
Copy-Item $jar (Join-Path $out "backend/backend.jar")
Copy-Item $runtime (Join-Path $out "backend/runtime") -Recurse

@"
AI Shell $version

1. Set the DEEPSEEK_API_KEY environment variable before starting AI Shell.
2. Run ai-shell.exe. It starts its own backend and needs no installed Java.
3. Hosts, conversations and audit logs live in %APPDATA%\com.aishell.desktop.

The backend is backend/backend.jar, run by the Java runtime in backend/runtime.
"@ | Set-Content -Path (Join-Path $out "README.txt") -Encoding UTF8

if (Test-Path $zip) {
  Remove-Item -Force $zip
}
Compress-Archive -Path (Join-Path $out "*") -DestinationPath $zip
Write-Host "==> portable package: $zip"
