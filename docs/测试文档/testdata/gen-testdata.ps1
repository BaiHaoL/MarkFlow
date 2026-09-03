# Phase 5 压测数据生成脚本（在 PC 上运行，产物用 adb push 到手机）
# 用法：powershell -ExecutionPolicy Bypass -File gen-testdata.ps1
$ErrorActionPreference = "Stop"
$dir = $PSScriptRoot
New-Item -ItemType Directory -Force -Path $dir | Out-Null

$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$utf8Bom   = New-Object System.Text.UTF8Encoding($true)
$gbk       = [System.Text.Encoding]::GetEncoding(936)
$utf16le   = New-Object System.Text.UnicodeEncoding($false, $true)  # LE + BOM

# 行模板：中文句子 + 行号，便于定位与写回比对
$line = "这是用于 MarkFlow 大 TXT 压测的中文小说文本行，第 {0} 行，包含中文内容以验证分页加载、滚动、随机跳块、分段编辑与字节级写回正确性。"

function Write-Lines($path, $count, $enc) {
    $sb = New-Object System.Text.StringBuilder
    for ($i = 0; $i -lt $count; $i++) {
        [void]$sb.AppendLine(($line -f $i))
    }
    [System.IO.File]::WriteAllText($path, $sb.ToString(), $enc)
    $mb = [math]::Round((Get-Item $path).Length / 1MB, 1)
    Write-Output ((Split-Path $path -Leaf) + "  " + $mb + " MB")
}

# 1. 小文件（阈值内）
Write-Lines (Join-Path $dir "small_utf8.txt") 2000 $utf8NoBom     # ~0.3MB
# 2. 跨阈值
Write-Lines (Join-Path $dir "cross_utf8.txt") 5500 $utf8NoBom     # ~0.8MB
# 3. 中文件 GBK
Write-Lines (Join-Path $dir "mid_gbk.txt") 80000 $gbk             # ~10MB
# 4. 大文件 UTF-8
Write-Lines (Join-Path $dir "big_utf8.txt") 270000 $utf8NoBom     # ~50MB

# 5. UTF-8 带 BOM（小）
Write-Lines (Join-Path $dir "bom_utf8.txt") 5 $utf8Bom
# 6. UTF-16LE 带 BOM（小）
Write-Lines (Join-Path $dir "utf16.txt") 5 $utf16le
# 7. 超长单行（单行 > 10KB，验证单行超长兜底）
$longLine = "A" * 51200 + "结尾标记"
[System.IO.File]::WriteAllText((Join-Path $dir "longline.txt"), $longLine, $utf8NoBom)
Write-Output ("longline.txt  {0:N1} MB" -f (Get-Item (Join-Path $dir "longline.txt")).Length / 1MB)

# 输出 hash 清单（写回比对的基准）
Write-Output "`n--- SHA256 ---"
Get-ChildItem $dir -Filter "*.txt" | ForEach-Object {
    $h = (Get-FileHash $_.FullName -Algorithm SHA256).Hash
    Write-Output ("{0}`t{1}" -f $_.Name, $h)
}
