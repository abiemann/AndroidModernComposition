[CmdletBinding()]
param(
    [string]$PythonExecutable = 'python',
    [ValidateRange(1024, 65535)][int]$Port = 8080
)

# Run from any directory. Requires an already-booted Android emulator, an SDK,
# and the Gradle JDK configured as described in README. No external service is used.
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$toolsRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot '.tools'))
$runDirectory = [IO.Path]::GetFullPath((Join-Path $toolsRoot ('http-integration-' + [Guid]::NewGuid().ToString('N'))))
$pythonPath = (Get-Command $PythonExecutable -CommandType Application -ErrorAction Stop).Source
$serverPath = Join-Path $projectRoot 'server\fieldnotes_server.py'
$wrapperPath = Join-Path $projectRoot 'gradlew.bat'
if (!(Test-Path -LiteralPath $serverPath) -or !(Test-Path -LiteralPath $wrapperPath)) {
    throw 'Run this script from a complete Field Notes checkout.'
}
if ($pythonPath -like '*\Microsoft\WindowsApps\*') {
    throw 'Specify a real Python executable with -PythonExecutable; the Windows Store alias cannot establish server process ownership.'
}

# Refuse to share a port with an existing service. The readiness check below also
# verifies the listener belongs to the exact process we start, closing the race.
$probe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $Port)
$probe.Server.ExclusiveAddressUse = $true
try {
    $probe.Start()
} catch {
    throw "Port $Port is unavailable. Stop the existing service or choose another port; no existing server will be used."
} finally {
    $probe.Stop()
}
Get-Command Get-NetTCPConnection -ErrorAction Stop | Out-Null

$tokenBytes = [byte[]]::new(32)
$random = [Security.Cryptography.RandomNumberGenerator]::Create()
try { $random.GetBytes($tokenBytes) } finally { $random.Dispose() }
$token = [Convert]::ToBase64String($tokenBytes)
[Array]::Clear($tokenBytes, 0, $tokenBytes.Length)

$tokenProperty = 'ORG_GRADLE_PROJECT_android.testInstrumentationRunnerArguments.serverToken'
$previousToken = [Environment]::GetEnvironmentVariable('FIELDNOTES_TOKEN', 'Process')
$previousProperty = [Environment]::GetEnvironmentVariable($tokenProperty, 'Process')
$server = $null
$buildExitCode = 1
$originalLocation = Get-Location

try {
    New-Item -ItemType Directory -Path $runDirectory -Force | Out-Null
    $databasePath = Join-Path $runDirectory 'notes.sqlite3'
    [Environment]::SetEnvironmentVariable('FIELDNOTES_TOKEN', $token, 'Process')
    # Paths are quoted for Start-Process's Windows command-line serialization.
    $serverArguments = @('-u', "`"$serverPath`"", '--host', '127.0.0.1', '--port', $Port,
        '--database', "`"$databasePath`"")
    $server = Start-Process -FilePath $pythonPath -ArgumentList $serverArguments `
        -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $runDirectory 'server.stdout.log') `
        -RedirectStandardError (Join-Path $runDirectory 'server.stderr.log')
    [Environment]::SetEnvironmentVariable('FIELDNOTES_TOKEN', $previousToken, 'Process')

    $ready = $false
    $deadline = [DateTime]::UtcNow.AddSeconds(20)
    while ([DateTime]::UtcNow -lt $deadline) {
        $server.Refresh()
        if ($server.HasExited) {
            throw "The isolated server exited before readiness (exit $($server.ExitCode)); no existing service was used."
        }
        $listeners = @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
        if ($listeners.Count -gt 0) {
            if ($listeners | Where-Object { $_.OwningProcess -ne $server.Id }) {
                throw "Port $Port belongs to a different process. Refusing to send test credentials or run tests."
            }
            try {
                $snapshot = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/v1/notes" `
                    -Headers @{ Authorization = "Bearer $token" } -TimeoutSec 2
                $projects = @($snapshot.projects | ForEach-Object { $_.id } | Sort-Object)
                if (($projects -join ',') -eq 'field,study' -and @($snapshot.notes).Count -eq 0) {
                    $ready = $true
                    break
                }
            } catch {
                # Bounded startup retry. Never print a request/header or token value.
            }
        }
        Start-Sleep -Milliseconds 200
    }
    if (!$ready) { throw 'The isolated server did not return its authenticated empty snapshot within 20 seconds.' }

    Write-Host "Isolated server ready on port $Port. Running Android HTTP integration against the emulator."
    # Gradle maps ORG_GRADLE_PROJECT_* into project properties, including dotted
    # instrumentation argument names. Keep the credential out of command output.
    [Environment]::SetEnvironmentVariable($tokenProperty, $token, 'Process')
    Set-Location -LiteralPath $projectRoot
    & $wrapperPath ':app:connectedLocalDebugAndroidTest' `
        '-Pandroid.testInstrumentationRunnerArguments.class=dev.fieldnotes.LocalServerIntegrationTest' `
        "-Pandroid.testInstrumentationRunnerArguments.baseUrl=http://10.0.2.2:$Port/" `
        '--no-daemon' '--no-parallel' '--console=plain'
    $buildExitCode = $LASTEXITCODE
} finally {
    [Environment]::SetEnvironmentVariable('FIELDNOTES_TOKEN', $previousToken, 'Process')
    [Environment]::SetEnvironmentVariable($tokenProperty, $previousProperty, 'Process')
    Set-Location -LiteralPath $originalLocation.Path
    if ($null -ne $server) {
        $server.Refresh()
        if (!$server.HasExited) {
            # The Process object is the one returned by our launch; never stop a
            # process discovered by port/name, or another user-owned server.
            $server.Kill()
            $server.WaitForExit(5000) | Out-Null
        }
        $server.Dispose()
    }
    if ($buildExitCode -ne 0 -and (Test-Path -LiteralPath $runDirectory)) {
        $verificationDirectory = Join-Path $projectRoot 'verification'
        New-Item -ItemType Directory -Path $verificationDirectory -Force | Out-Null
        $diagnostics = @("Isolated local server diagnostics; Gradle exit code: $buildExitCode")
        foreach ($stream in @('stdout', 'stderr')) {
            $logPath = Join-Path $runDirectory "server.$stream.log"
            $diagnostics += "--- $stream ---"
            if (Test-Path -LiteralPath $logPath) {
                $contents = [IO.File]::ReadAllText($logPath)
                if (![string]::IsNullOrEmpty($token)) { $contents = $contents.Replace($token, '[REDACTED]') }
                $diagnostics += $contents
            }
        }
        [IO.File]::WriteAllText((Join-Path $verificationDirectory 'local-server.log'), ($diagnostics -join [Environment]::NewLine))
        Write-Host 'Server diagnostics saved to verification/local-server.log.'
    }
    $token = $null
    # Validate the absolute target before recursive cleanup. Only this unique run
    # directory under the checkout's ignored .tools directory can be removed.
    $expectedPrefix = $toolsRoot.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    if (!$runDirectory.StartsWith($expectedPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Refusing cleanup outside the workspace test directory.'
    }
    if (Test-Path -LiteralPath $runDirectory) {
        Remove-Item -LiteralPath $runDirectory -Recurse -Force
    }
}

exit $buildExitCode
