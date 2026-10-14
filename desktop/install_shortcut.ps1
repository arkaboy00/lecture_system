# Creates a desktop shortcut "Лекции — синхронизация" that opens the sync window
# (pythonw: no console). Nothing is installed as a service and nothing autostarts.
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$pythonw = (Get-Command pythonw -ErrorAction SilentlyContinue).Source
if (-not $pythonw) { $pythonw = Join-Path (Split-Path (Get-Command python).Source) "pythonw.exe" }
$desktop = [Environment]::GetFolderPath("Desktop")
$shell = New-Object -ComObject WScript.Shell
$link = $shell.CreateShortcut((Join-Path $desktop "Лекции — синхронизация.lnk"))
$link.TargetPath = $pythonw
$link.Arguments = "`"$(Join-Path $here 'LectureSync.pyw')`""
$link.WorkingDirectory = $here
$link.IconLocation = (Join-Path $here "icon.ico")
$link.Description = "Забрать лекции с телефона по Wi-Fi"
$link.Save()
Write-Output "Shortcut created on $desktop"
