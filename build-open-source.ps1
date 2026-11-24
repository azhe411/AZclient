param(
    [string]$JavaHome = $env:LITECLIENT_JAVA_HOME,
    [string]$AttachRuntime = '',
    [string]$CscPath = 'C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe',
    [string]$OutputExe = ''
)

$ErrorActionPreference = 'Stop'
$Root = $PSScriptRoot
$Build = Join-Path $Root 'build-oss'
if ([string]::IsNullOrWhiteSpace($OutputExe)) { $OutputExe = Join-Path $Build 'AZclient-8.0.0-offline-oss.exe' }
if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    $javac = Get-Command javac.exe -ErrorAction SilentlyContinue
    if ($null -ne $javac) { $JavaHome = Split-Path -Parent $javac.Source }
}
if (-not (Test-Path (Join-Path $JavaHome 'javac.exe'))) { throw '需要 JDK 8：请传 -JavaHome <JDK8\bin>' }
if (-not (Test-Path $CscPath)) { throw '找不到 csc.exe' }
$Java = (Resolve-Path $JavaHome).Path
$Tools = [IO.Path]::GetFullPath((Join-Path $Java '..\lib\tools.jar'))

if (Test-Path $Build) { Remove-Item -LiteralPath $Build -Recurse -Force }
New-Item -ItemType Directory -Path $Build | Out-Null
$Attach = Join-Path $Build 'attach'
New-Item -ItemType Directory -Path $Attach | Out-Null
if (-not [string]::IsNullOrWhiteSpace($AttachRuntime)) {
    $sourceAttach = (Resolve-Path -LiteralPath $AttachRuntime).Path
    Copy-Item (Join-Path $sourceAttach '*') $Attach -Recurse -Force
} elseif (Test-Path -LiteralPath $Tools) {
    Push-Location $Attach
    try {
        & (Join-Path $Java 'jar.exe') xf $Tools com/sun/tools/attach sun/tools/attach sun/jvmstat
        if ($LASTEXITCODE -ne 0) { throw '无法从 tools.jar 提取 Attach 实现' }
    } finally { Pop-Location }
    New-Item -ItemType Directory -Force -Path (Join-Path $Attach 'META-INF\services') | Out-Null
    Set-Content -LiteralPath (Join-Path $Attach 'META-INF\services\com.sun.tools.attach.spi.AttachProvider') -Value 'sun.tools.attach.WindowsAttachProvider' -Encoding Ascii
} else {
    throw 'JDK 缺少 lib\tools.jar。请换完整 JDK 8，或用 -AttachRuntime 指向已提取的 Java 8 Attach 目录。'
}
$attachFiles = (Get-ChildItem -LiteralPath $Attach -Recurse -File | Measure-Object).Count
if ($attachFiles -lt 90 -or -not (Test-Path (Join-Path $Attach 'sun\tools\attach\WindowsAttachProvider.class')) `
        -or -not (Test-Path (Join-Path $Attach 'META-INF\services\com.sun.tools.attach.spi.AttachProvider'))) {
    throw "Java 8 Attach 运行时不完整：$attachFiles 个文件"
}

$Agent = Join-Path $Build 'agent'
New-Item -ItemType Directory -Path $Agent | Out-Null
$agentSources = Get-ChildItem -LiteralPath (Join-Path $Root 'src-agent\com\lite\agent') -Filter *.java | ForEach-Object FullName
& (Join-Path $Java 'javac.exe') -encoding UTF-8 -cp ((Join-Path $Root 'lib\asm.jar') + ';' + $Attach) -d $Agent $agentSources
if ($LASTEXITCODE -ne 0) { throw 'agent 编译失败' }

$Asm = Join-Path $Build 'asm'
New-Item -ItemType Directory -Path $Asm | Out-Null
Push-Location $Asm
try { & (Join-Path $Java 'jar.exe') xf (Join-Path $Root 'lib\asm.jar') } finally { Pop-Location }
$AgentJarRoot = Join-Path $Build 'agent-jar'
New-Item -ItemType Directory -Path $AgentJarRoot | Out-Null
Copy-Item (Join-Path $Agent '*') $AgentJarRoot -Recurse -Force
Copy-Item (Join-Path $Asm '*') $AgentJarRoot -Recurse -Force
Copy-Item (Join-Path $Attach '*') $AgentJarRoot -Recurse -Force
Copy-Item (Join-Path $Root 'src-agent\resources\*') $AgentJarRoot -Recurse -Force
Remove-Item (Join-Path $AgentJarRoot 'META-INF\MANIFEST.MF') -Force -ErrorAction SilentlyContinue
$AgentJar = Join-Path $Build 'lite-agent.jar'
& (Join-Path $Java 'jar.exe') cfm $AgentJar (Join-Path $Root 'MANIFEST.MF') -C $AgentJarRoot .
if ($LASTEXITCODE -ne 0) { throw 'agent 打包失败' }

$Injector = Join-Path $Build 'injector'
New-Item -ItemType Directory -Path $Injector | Out-Null
$injectorSources = Get-ChildItem -LiteralPath (Join-Path $Root 'src-injector\com\lite\injector') -Filter *.java | ForEach-Object FullName
& (Join-Path $Java 'javac.exe') -encoding UTF-8 -cp $Attach -d $Injector $injectorSources
if ($LASTEXITCODE -ne 0) { throw 'injector 编译失败' }

$Windows = Join-Path $Build 'windows'
New-Item -ItemType Directory -Path $Windows | Out-Null
& $CscPath /nologo /target:exe /reference:System.Management.dll ("/out:" + (Join-Path $Windows 'process-list.exe')) (Join-Path $Root 'src-launcher\ProcessList.cs')
if ($LASTEXITCODE -ne 0) { throw '进程助手编译失败' }
$InjectorJarRoot = Join-Path $Build 'injector-jar'
New-Item -ItemType Directory -Path $InjectorJarRoot | Out-Null
Copy-Item (Join-Path $Injector '*') $InjectorJarRoot -Recurse -Force
Copy-Item (Join-Path $Attach '*') $InjectorJarRoot -Recurse -Force
New-Item -ItemType Directory -Path (Join-Path $InjectorJarRoot 'web'),(Join-Path $InjectorJarRoot 'windows') | Out-Null
Copy-Item (Join-Path $Root 'src-injector\resources\web\*') (Join-Path $InjectorJarRoot 'web') -Force
Copy-Item (Join-Path $Windows 'process-list.exe') (Join-Path $InjectorJarRoot 'windows\process-list.exe') -Force
$InjectorManifest = Join-Path $Build 'injector.mf'
Set-Content -LiteralPath $InjectorManifest -Value "Main-Class: com.lite.injector.AzMain`r`n" -Encoding Ascii
$InjectorJar = Join-Path $Build 'lite-injector.jar'
& (Join-Path $Java 'jar.exe') cfm $InjectorJar $InjectorManifest -C $InjectorJarRoot .
if ($LASTEXITCODE -ne 0) { throw 'injector 打包失败' }

$entries = & (Join-Path $Java 'jar.exe') tf $AgentJar
if ($entries -match '^com/lite/auth/') { throw '离线 agent 不应包含网络认证类' }
$entries = & (Join-Path $Java 'jar.exe') tf $InjectorJar
if ($entries -match '^com/lite/auth/') { throw '离线 injector 不应包含网络认证类' }

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $OutputExe) | Out-Null
& $CscPath /nologo /target:winexe /reference:System.Windows.Forms.dll `
    ("/out:" + $OutputExe) `
    ("/win32icon:" + (Join-Path $Root 'src-launcher\AZclient.ico')) `
    ("/win32manifest:" + (Join-Path $Root 'src-launcher\AZclient.manifest')) `
    ("/resource:" + $InjectorJar + ',InjectorJar') `
    ("/resource:" + $AgentJar + ',AgentJar') `
    ("/resource:" + (Join-Path $Root 'LICENSE') + ',OpenSourceLicense') `
    (Join-Path $Root 'src-launcher\OpenSourceLauncher.cs')
if ($LASTEXITCODE -ne 0) { throw 'EXE 编译失败' }
& $OutputExe --verify-release
if ($LASTEXITCODE -ne 0) { throw 'EXE 资源自检失败' }
Get-FileHash -Algorithm SHA256 -LiteralPath $OutputExe
Write-Host "完成：$OutputExe"
