$ErrorActionPreference = 'Stop'

# dtlvnative 1.1.5 links the MSVC C++ and OpenMP runtimes dynamically.
# JavaCPP's final "no jniDTLV in java.library.path" hides a missing dependency.
$runtimeDlls = @('MSVCP140.dll', 'VCRUNTIME140.dll', 'VCRUNTIME140_1.dll', 'VCOMP140.dll')
function Get-MissingRuntimeDlls {
    foreach ($dll in $runtimeDlls) {
        $handle = [IntPtr]::Zero
        if ([System.Runtime.InteropServices.NativeLibrary]::TryLoad($dll, [ref]$handle)) {
            [System.Runtime.InteropServices.NativeLibrary]::Free($handle)
        } else {
            $dll
        }
    }
}

$missing = @(Get-MissingRuntimeDlls)
if ($missing.Count -gt 0) {
    Write-Host "Missing Datalevin runtime dependencies: $($missing -join ', ')"
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'Install the x64 Visual C++ Redistributable on this runner: https://aka.ms/vc14/vc_redist.x64.exe'
    }
    $installer = Join-Path $env:RUNNER_TEMP 'vc_redist.x64.exe'
    Invoke-WebRequest 'https://aka.ms/vc14/vc_redist.x64.exe' -OutFile $installer
    try {
        $process = Start-Process $installer -ArgumentList '/install', '/quiet', '/norestart' -Wait -PassThru
        if ($process.ExitCode -notin @(0, 1638, 3010)) {
            throw "Visual C++ Redistributable installation failed: $($process.ExitCode)"
        }
    } finally {
        Remove-Item $installer -ErrorAction SilentlyContinue
    }
    $missing = @(Get-MissingRuntimeDlls)
    if ($missing.Count -gt 0) {
        throw "Datalevin runtime dependencies are still unavailable: $($missing -join ', ')"
    }
}

# Fail with the original loader cause before a failed static initializer poisons
# every store-using integration test in the JVM.
clojure -M:natives-windows -e '(try (Class/forName "datalevin.dtlvnative.DTLV") (println "Datalevin native library loaded") (catch Throwable e (.printStackTrace e) (System/exit 1)))'
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
