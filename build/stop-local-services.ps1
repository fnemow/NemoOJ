<#
  停止本地以 `java -jar` 启动的 NemoOJ 服务实例。

  用途：Windows 上运行中的服务会锁定 target 下的可执行 JAR，
        导致下一次 `mvn clean package` 无法删除产物。
        在重新构建前执行本脚本即可释放文件占用。

  用法（PowerShell）：
      pwsh -File build/stop-local-services.ps1

  说明：只匹配命令行中包含本仓库 target 目录的 java 进程，
        不会影响 IDE、Maven 守护进程或其它 Java 应用。
#>
[CmdletBinding()]
param(
    [switch]$Quiet
)

# 注意：必须同时匹配"绝对路径"与"相对路径"两种启动方式。
# 用相对路径启动（java -jar nemo-sandbox/target/xxx.jar）时命令行里
# 不含仓库绝对路径，只按绝对路径匹配会漏掉，导致构建时文件被占用。
$patterns = @(
    '*OJ-backend-*target*',
    '*nemo-sandbox*target*',
    '*nemo-sandbox/*/target/*',
    '*-jar .*OJ-backend-*/target/*'
)

$targets = Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
    Where-Object {
        $cmd = $_.CommandLine
        $cmd -and ($patterns | Where-Object { $cmd -like $_ })
    }

if (-not $targets) {
    if (-not $Quiet) { Write-Host '没有匹配的本地服务进程。' }
    exit 0
}

foreach ($p in $targets) {
    if (-not $Quiet) {
        $preview = $p.CommandLine.Substring(0, [Math]::Min(110, $p.CommandLine.Length))
        Write-Host "停止 PID $($p.ProcessId): $preview"
    }
    Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
}

Start-Sleep -Seconds 2

$remaining = Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
    Where-Object {
        $cmd = $_.CommandLine
        $cmd -and ($patterns | Where-Object { $cmd -like $_ })
    }

if ($remaining) {
    Write-Warning "仍有 $($remaining.Count) 个进程未停止。"
    exit 1
}

if (-not $Quiet) { Write-Host '已全部停止，可以重新构建。' }
