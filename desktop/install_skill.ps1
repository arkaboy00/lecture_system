# Installs the Claude Code skill "konspekt" (notes from processed lectures) for this user:
# copies claude_skill\konspekt to %USERPROFILE%\.claude\skills\konspekt. Run again after
# editing the skill in the repository.
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$target = Join-Path $env:USERPROFILE ".claude\skills\konspekt"
New-Item -ItemType Directory -Force $target | Out-Null
Copy-Item (Join-Path $here "claude_skill\konspekt\*") $target -Recurse -Force
Write-Output "Skill installed to $target"
