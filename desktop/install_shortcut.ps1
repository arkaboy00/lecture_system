# Creates a desktop shortcut "Лекции" that opens the sync and processing window
# (pythonw: no console). Nothing is installed as a service and nothing autostarts.
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
# The project venv has the processing dependencies (faster-whisper, CUDA libraries).
$pythonw = Join-Path $here ".venv\Scripts\pythonw.exe"
if (-not (Test-Path $pythonw)) { $pythonw = (Get-Command pythonw -ErrorAction SilentlyContinue).Source }
if (-not $pythonw) { $pythonw = Join-Path (Split-Path (Get-Command python).Source) "pythonw.exe" }
$desktop = [Environment]::GetFolderPath("Desktop")
$shell = New-Object -ComObject WScript.Shell
# Earlier versions called it "Лекции — синхронизация".
Remove-Item (Join-Path $desktop "Лекции — синхронизация.lnk") -ErrorAction SilentlyContinue
$link = $shell.CreateShortcut((Join-Path $desktop "Лекции.lnk"))
$link.TargetPath = $pythonw
$link.Arguments = "`"$(Join-Path $here 'LectureSync.pyw')`""
$link.WorkingDirectory = $here
$link.IconLocation = (Join-Path $here "icon.ico")
$link.Description = "Забрать лекции с телефона по Wi-Fi и обработать"
$link.Save()
Write-Output "Shortcut created on $desktop"
