import sys
head='''<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>%s</title>
<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/@tabler/icons-webfont@3.19.0/dist/tabler-icons.min.css">
<style>:root{--font-sans:system-ui,-apple-system,"Segoe UI",sans-serif;--text-secondary:#5F5E5A;--surface-1:#F1EFE8}
body{margin:0;padding:24px;background:#FAFAF8;font-family:var(--font-sans)}h1{font-size:18px;font-weight:500;margin:0 0 4px}p.d{margin:0 0 16px;color:#5F5E5A;font-size:14px}.sr-only{position:absolute;width:1px;height:1px;overflow:hidden;clip:rect(0 0 0 0)}</style></head><body>
<h1>%s</h1><p class="d">%s</p>
'''
title,desc,src,out=sys.argv[1:5]
open(out,'w').write(head%(title,title,desc)+open(src).read()+'\n</body></html>\n')
