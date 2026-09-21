# Task Lens Offline Models Push Utility (Windows PowerShell)
# Pushes offline AI model files to the Task Lens application sandbox on device/emulator.

param (
    [string]$ModelFile = "",
    [string]$TargetName = ""
)

$adbPath = (Get-Command adb -ErrorAction SilentlyContinue)?.Source
if (-not $adbPath) {
    $defaultSdkAdb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
    if (Test-Path $defaultSdkAdb) {
        $adbPath = $defaultSdkAdb
    } else {
        Write-Error "Could not locate adb.exe. Please ensure Android SDK is installed."
        exit 1
    }
}

Write-Host "Using ADB: $adbPath" -ForegroundColor Cyan

# Check connected devices
$devices = & $adbPath devices | Select-String -Pattern "device$"
if ($devices.Count -eq 0) {
    Write-Warning "No authorized device or emulator connected. Please start your device/emulator and authorize USB debugging."
    exit 1
}

Write-Host "Found device: $($devices[0].Line)" -ForegroundColor Green

# Ensure target models directory exists in app sandbox
& $adbPath shell "run-as com.tasklens mkdir -p files/models"

if ($ModelFile -and (Test-Path $ModelFile)) {
    $fileName = if ($TargetName) { $TargetName } else { Split-Path $ModelFile -Leaf }
    Write-Host "Pushing $ModelFile to files/models/$fileName..." -ForegroundColor Yellow
    & $adbPath push $ModelFile "/data/local/tmp/$fileName"
    & $adbPath shell "run-as com.tasklens cp /data/local/tmp/$fileName files/models/$fileName"
    & $adbPath shell "rm -f /data/local/tmp/$fileName"
    Write-Host "Successfully pushed $fileName!" -ForegroundColor Green
} else {
    Write-Host "`nExisting models in Task Lens sandbox:" -ForegroundColor Cyan
    & $adbPath shell "run-as com.tasklens ls -lh files/models"
    Write-Host "`nUsage:"
    Write-Host "  .\tools\push_models.ps1 -ModelFile path\to\coach.task -TargetName coach.task"
    Write-Host "  .\tools\push_models.ps1 -ModelFile path\to\detector.tflite -TargetName object_detector.tflite"
    Write-Host "  .\tools\push_models.ps1 -ModelFile path\to\coco.tflite -TargetName object_detector_coco.tflite"
}
