from __future__ import annotations
import html, json, os, re, time
from pathlib import Path
import requests
import psycopg2

ROOT=Path(__file__).resolve().parent.parent
ENV=ROOT/'interview-agent'/'.env'
CONFIG={}
for raw in ENV.read_text(encoding='utf-8').splitlines():
    line=raw.strip()
    if line and not line.startswith('#') and '=' in line:
        k,v=line.split('=',1); CONFIG[k.strip()]=v.strip()
API_KEY=CONFIG.get('OPENAI_API_KEY','')
BASE=CONFIG.get('OPENAI_BASE_URL','https://api.deepseek.com/v1').rstrip('/')
MODEL=CONFIG.get('OPENAI_MODEL','deepseek-chat')
if not API_KEY:
    raise SystemExit('missing API key')

SIMPLE={
    'java':'Java开发工程师','python':'Python开发工程师','c/c++':'C/C++开发工程师','c++':'C++开发工程师',
    'devops':'DevOps工程师','sre/devops':'SRE/DevOps工程师','python coder':'Python开发工程师',
    'web前端':'Web前端开发工程师','web前端工程师':'Web前端开发工程师'
}

def clean(text):
    text=html.unescape(text or '')
    text=re.sub(r'<script.*?</script>|<style.*?</style>',' ',text,flags=re.I|re.S)
    text=re.sub(r'<[^>]+>','\n',text)
    text=re.sub(r'[ \t\r\f\v]+',' ',text)
    text=re.sub(r'\n\s*\n+', '\n', text)
    return text.strip()

def has_cjk(text): return bool(re.search(r'[\u4e00-\u9fff]', text or ''))
def has_latin(text): return bool(re.search(r'[A-Za-z]', text or ''))

def call_json(prompt):
    payload={
        'model':MODEL,'temperature':0.1,'max_tokens':5000,
        'messages':[{'role':'system','content':'你是专业的招聘信息本地化译者。只输出合法 JSON，不输出 Markdown。'}, {'role':'user','content':prompt}],
        'response_format':{'type':'json_object'}
    }
    last=None
    for attempt in range(3):
        try:
            r=requests.post(BASE+'/chat/completions',headers={'Authorization':f'Bearer {API_KEY}','Content-Type':'application/json'},json=payload,timeout=180)
            if r.status_code>=400 and payload.get('response_format'):
                payload.pop('response_format',None); continue
            r.raise_for_status()
            content=r.json()['choices'][0]['message']['content'].strip()
            content=re.sub(r'^```json\s*|\s*```$','',content,flags=re.I|re.S)
            return json.loads(content)
        except Exception as exc:
            last=exc; time.sleep(1.5*(attempt+1))
    raise RuntimeError(str(last))

conn=psycopg2.connect(host='127.0.0.1',port=5432,user='careernav',dbname='careernav')
cur=conn.cursor()
cur.execute("select id,title,coalesce(job_description,description,''),coalesce(job_requirements,'') from jobs where status='ACTIVE'")
rows=cur.fetchall()
english_title_jobs=[r for r in rows if has_latin(r[1]) and not has_cjk(r[1])]
detail_jobs=[r for r in rows if (has_latin(r[2]) and not has_cjk(r[2])) or (has_latin(r[3]) and not has_cjk(r[3]))]
print(f'english_title_jobs={len(english_title_jobs)} detail_jobs={len(detail_jobs)}',flush=True)

title_map={}
remaining=[]
for _,title,_,_ in english_title_jobs:
    key=(title or '').strip().lower()
    if key in SIMPLE: title_map[title]=SIMPLE[key]
    elif title not in remaining: remaining.append(title)

for start in range(0,len(remaining),20):
    batch=remaining[start:start+20]
    numbered=[{'n':i+1,'title':t} for i,t in enumerate(batch)]
    prompt=f'''把以下招聘岗位名称翻译成简洁、自然的简体中文岗位名。保留 Java、Python、C++、DevOps、SRE、AI、RAG、Android、BSP、Linux、IoT 等技术专名。不要添加职责。只返回 {{"items":[{{"n":1,"title":"中文岗位名"}}]}}。\n{json.dumps(numbered,ensure_ascii=False)}'''
    result=call_json(prompt)
    for item in result.get('items',[]):
        try: title_map[batch[int(item['n'])-1]]=str(item['title']).strip()
        except Exception: pass
    print(f'titles {min(start+20,len(remaining))}/{len(remaining)}',flush=True)

for jid,title,_,_ in english_title_jobs:
    translated=title_map.get(title)
    if translated:
        cur.execute('update jobs set display_title=%s where id=%s',(translated,jid))
conn.commit()

details_by_id={r[0]:r for r in detail_jobs}
ids=list(details_by_id)
for start in range(0,len(ids),3):
    batch=[details_by_id[i] for i in ids[start:start+3]]
    payload=[]
    for jid,title,jd,req in batch:
        payload.append({'id':jid,'title':title,'description':clean(jd)[:5000],'requirements':clean(req)[:3500]})
    prompt=f'''将以下招聘岗位信息忠实翻译成简体中文。技术、产品、公司专名保留原文，例如 Java、Python、AWS、Kubernetes、Android、BSP。不要省略关键职责，不要添加原文没有的信息。只返回 {{"items":[{{"id":1,"title":"中文岗位名","description":"中文职责","requirements":"中文任职要求"}}]}}。\n{json.dumps(payload,ensure_ascii=False)}'''
    result=call_json(prompt)
    for item in result.get('items',[]):
        try:
            jid=int(item['id']); row=details_by_id[jid]
            dtitle=str(item.get('title') or title_map.get(row[1]) or row[1]).strip()
            desc=str(item.get('description') or '').strip()
            req=str(item.get('requirements') or '').strip()
            cur.execute('update jobs set display_title=%s, display_description=%s, display_requirements=%s where id=%s',(dtitle,desc or None,req or None,jid))
        except Exception:
            continue
    conn.commit()
    print(f'details {min(start+3,len(ids))}/{len(ids)}',flush=True)

cur.execute("select count(*) from jobs where status='ACTIVE' and display_title is not null and btrim(display_title)<>''")
localized_titles=cur.fetchone()[0]
cur.execute("select count(*) from jobs where status='ACTIVE' and display_description is not null and btrim(display_description)<>''")
localized_desc=cur.fetchone()[0]
cur.execute("select count(*) from jobs where status='ACTIVE' and display_requirements is not null and btrim(display_requirements)<>''")
localized_req=cur.fetchone()[0]
report={'englishTitleJobs':len(english_title_jobs),'detailJobs':len(detail_jobs),'localizedTitles':localized_titles,'localizedDescriptions':localized_desc,'localizedRequirements':localized_req}
(ROOT/'output'/'job_localization_report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(report,ensure_ascii=False),flush=True)
conn.close()

