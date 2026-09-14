# 项目本地构建环境（不修改系统环境变量，仅当前会话生效）
# 用法：  . .\maven\env.ps1        （在 PowerShell 中 dot-source）
#         mvn -gs maven\settings.xml clean package

$env:JAVA_HOME = 'D:\jdk\jdk21'
$env:MAVEN_HOME = 'D:\devsoft\apache-maven-3.9.4'
$env:MAVEN_OPTS = '-Xmx1024m -Dfile.encoding=UTF-8'
$env:Path = "$env:JAVA_HOME\bin;$env:MAVEN_HOME\bin;$env:Path"

Write-Host "JAVA_HOME  = $env:JAVA_HOME"
Write-Host "MAVEN_HOME = $env:MAVEN_HOME"
& "$env:JAVA_HOME\bin\java.exe" -version
& "$env:MAVEN_HOME\bin\mvn.cmd" -v
