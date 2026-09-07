import re, os

for f in ['第2章_笔记(1).md', '# C++知识点（母版）.md']:
    path = os.path.join('docs/测试文档', f)
    s = open(path, encoding='utf-8').read()
    spans = 0
    spans += len(re.findall(r'^#{1,6}\s.*$', s, re.M))
    spans += len(re.findall(r'\*\*\*.+?\*\*\*', s))
    spans += len(re.findall(r'(?<!\*)\*\*(?!\*)(.+?)(?<!\*)\*\*(?!\*)', s))
    spans += len(re.findall(r'(?<!\*)\*(?!\*)(.+?)(?<!\*)\*(?!\*)', s))
    spans += len(re.findall(r'~~(?!~)(.+?)(?<!~)~~', s))
    spans += len(re.findall(r'`[^`\n]+?`', s))
    spans += len(re.findall(r'\[[^\]]+\]\([^)]+\)', s))
    spans += len(re.findall(r'^(\s*)([-*+])\s', s, re.M))
    spans += len(re.findall(r'^(\s*)(\d+[.)])\s', s, re.M))
    spans += len(re.findall(r'^(>+)\s?', s, re.M))
    spans += len(re.findall(r'^(\s*[-*_]{3,})\s*$', s, re.M))
    print(f, len(s), 'chars,', spans, 'spans')
