# PowerShell wrapper: UTF-8 console + mvnw.cmd (fixes garbled Chinese on Windows)
$utf8 = [System.Text.UTF8Encoding]::new($false)
[Console]::InputEncoding = $utf8
[Console]::OutputEncoding = $utf8
$OutputEncoding = $utf8

& "$PSScriptRoot\mvnw.cmd" @args
exit $LASTEXITCODE
