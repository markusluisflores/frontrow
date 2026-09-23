# PostToolUse hook: format the one Java file Claude just wrote or edited.
# Reads the hook's JSON input from stdin; never blocks the edit (always exits 0).
$payload = [Console]::In.ReadToEnd() | ConvertFrom-Json
$file = $payload.tool_input.file_path
if (-not $file -or -not $file.EndsWith('.java')) { exit 0 }
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Push-Location $root
try {
    & "$root\mvnw.cmd" -q spotless:apply "-DspotlessFiles=$([regex]::Escape($file))" | Out-Null
} finally {
    Pop-Location
}
exit 0
