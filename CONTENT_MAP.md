# Content Source Map

Generated: 2025-12-30T16:38:58.236311Z

Method: curl checks + Playwright fallback for WAF-blocked hosts. BOE requires DigiCert intermediate at `/home/dami/daral1/tmp/boe-digicert-g2.pem` for TLS validation.

## Summary
- Sources checked: 48
- Mapping scope: robots.txt + sitemap discovery (robots + common paths)
- Raw outputs: `tmp/source-map.json`, `tmp/source-map-sitemaps.json`

## Sources by type
### Primary publication + laws
- uqn.gov.sa: base OK; robots missing (404); no sitemap found; defer (newspaper)
- boe.gov.sa: base OK; robots OK; sitemap non-XML/redirect; TLS requires DigiCert intermediate
- laws.boe.gov.sa: base OK; robots OK; no sitemap found; TLS requires DigiCert intermediate
- ncar.gov.sa: base OK; robots OK; sitemap non-XML/redirect

### Tax / Zakat / Customs + disputes
- zatca.gov.sa: base OK; robots OK; sitemap OK
- gstc.gov.sa: base OK; robots OK; sitemap OK

### Corporate / investment / labor
- mc.gov.sa: base OK; robots OK; sitemap OK
- saudibusiness.gov.sa: base OK; robots OK; sitemap non-XML/redirect
- misa.gov.sa: base OK; robots OK; sitemap OK
- investsaudi.sa: base OK; robots OK; sitemap non-XML/redirect
- hrsd.gov.sa: base OK; robots OK; sitemap OK

### Labor & HR platforms
- qiwa.sa: base OK; robots OK; sitemap non-XML/redirect
- muqeem.sa: base OK; robots OK; sitemap OK
- mudad.com.sa: base OK; robots OK; sitemap OK

### Government services / identity
- absher.sa: base redirect (302); robots 000; no sitemap found; defer (login required)
- www.moi.gov.sa: base OK; robots OK; no sitemap found

### Finance / capital markets
- rulebook.sama.gov.sa: base OK; robots OK; sitemap non-XML/redirect
- cma.org.sa: base OK; robots OK; sitemap OK

### Privacy / cybersecurity
- sdaia.gov.sa: base OK; robots OK; sitemap non-XML/redirect
- nca.gov.sa: base OK; robots OK; sitemap OK

### Competition & AML/CFT
- gac.gov.sa: base OK; robots OK; sitemap non-XML/redirect
- safiu.gov.sa: base 000; robots 000; no sitemap found

### Justice / courts / insolvency
- moj.gov.sa: base OK; robots OK; sitemap OK
- najiz.sa: base OK; robots missing (404); no sitemap found

### Municipal / housing / real estate
- momrah.gov.sa: base OK; robots OK; sitemap OK
- rega.gov.sa: base OK (playwright required); robots OK (playwright required); no sitemap found

### Industry / mining / industrial zones
- mim.gov.sa: base OK; robots OK; sitemap OK
- modon.gov.sa: base OK (playwright required); robots OK (playwright required); sitemap non-XML/redirect

### SME support
- monshaat.gov.sa: base OK; robots OK; sitemap OK

### Transport / logistics / aviation / ports
- tga.gov.sa: base OK; robots missing (404); no sitemap found
- gaca.gov.sa: base OK; robots OK; sitemap OK
- mawani.gov.sa: base OK; robots OK; sitemap non-XML/redirect

### Tourism
- mot.gov.sa: base OK; robots OK; sitemap OK

### Health
- moh.gov.sa: base OK; robots OK; sitemap OK

### Digital government / data policy
- dga.gov.sa: base OK; robots OK; no sitemap found
- ndmo.gov.sa: base 000; robots 000; no sitemap found

### Procurement / government selling
- mof.gov.sa: base OK; robots OK; sitemap non-XML/redirect
- etimad.sa: base OK; robots OK; sitemap non-XML/redirect

### Sector regulators
- cst.gov.sa: base OK; robots OK; sitemap OK
- www.sfda.gov.sa: base OK; robots OK; sitemap OK
- saso.gov.sa: base OK; robots OK; sitemap points to internal host
- www.mewa.gov.sa: base OK; robots OK; sitemap OK
- saip.gov.sa: base OK; robots OK; sitemap OK
- www.moenergy.gov.sa: base OK; robots OK; sitemap OK

### GCC + international reference
- www.gcc-sg.org: base OK; robots OK; sitemap non-XML/redirect
- www.oecd.org: base OK (playwright required); robots OK; sitemap blocked (403)

### National data meta-layer
- data.gov.sa: base OK; robots OK; sitemap non-XML/redirect
- my.gov.sa: base OK (playwright required); robots OK (playwright required); sitemap blocked (403)

## Details
### uqn.gov.sa
- Base: https://uqn.gov.sa/ -> 200 (curl)
- Robots: https://uqn.gov.sa/robots.txt -> 404 (curl)
- Sitemaps: none discovered
- Note: No sitemap discovered via robots/common paths

### boe.gov.sa
- Base: https://boe.gov.sa/en/Pages/ -> 200 (curl)
- Robots: https://boe.gov.sa/robots.txt -> 200 (curl)
- Sitemaps:
  - https://boe.gov.sa/sitemap.xml (common) -> 200 text/html (len=1679); non-XML content-type
  - https://boe.gov.sa/sitemap_index.xml (common) -> 200 text/html (len=1679); non-XML content-type
  - https://boe.gov.sa/sitemap-index.xml (common) -> 200 text/html (len=1679); non-XML content-type
  - https://boe.gov.sa/sitemap/sitemap.xml (common) -> 200 text/html (len=1679); non-XML content-type
  - https://boe.gov.sa/sitemap.xml.gz (common) -> 200 text/html (len=1679); non-XML content-type
  - https://boe.gov.sa/sitemap_index.xml.gz (common) -> 200 text/html (len=1679); non-XML content-type

### laws.boe.gov.sa
- Base: https://laws.boe.gov.sa/ -> 200 (curl)
- Robots: https://laws.boe.gov.sa/robots.txt -> 200 (curl)
- Sitemaps: none discovered
- Note: No sitemap discovered via robots/common paths
- Note: Listing URL: https://laws.boe.gov.sa/BoeLaws/Laws/Folders/2?FolderId=cf5e8242-b191-4200-ae90-0fb3c3a4c280&PartId=4a68f8e1-ff14-4a0d-8a07-ee67f2701019

### ncar.gov.sa
- Base: https://ncar.gov.sa/ -> 200 (curl)
- Robots: https://ncar.gov.sa/robots.txt -> 200 (curl)
- Sitemaps:
  - https://ncar.gov.sa/sitemap.xml (common) -> 200 text/html (len=14185); non-XML content-type
  - https://ncar.gov.sa/sitemap_index.xml (common) -> 200 text/html (len=14185); non-XML content-type
  - https://ncar.gov.sa/sitemap-index.xml (common) -> 200 text/html (len=14185); non-XML content-type
  - https://ncar.gov.sa/sitemap/sitemap.xml (common) -> 200 text/html (len=14185); non-XML content-type
  - https://ncar.gov.sa/sitemap.xml.gz (common) -> 200 text/html (len=14185); non-XML content-type
  - https://ncar.gov.sa/sitemap_index.xml.gz (common) -> 200 text/html (len=14185); non-XML content-type

### zatca.gov.sa
- Base: https://zatca.gov.sa/ -> 200 (curl)
- Robots: https://zatca.gov.sa/robots.txt -> 200 (curl)
- Sitemaps:
  - https://zatca.gov.sa/sitemap.xml (robots) -> 200 text/xml (len=426750)

### gstc.gov.sa
- Base: https://gstc.gov.sa/ -> 200 (curl)
- Robots: https://gstc.gov.sa/robots.txt -> 200 (curl)
- Sitemaps:
  - https://gstc.gov.sa:443/sitemap.xml (robots) -> 200 text/xml (len=15042)

### mc.gov.sa
- Base: https://mc.gov.sa/ -> 200 (curl)
- Robots: https://mc.gov.sa/robots.txt -> 200 (curl)
- Sitemaps:
  - https://mc.gov.sa/sitemap.xml (robots) -> 200 text/xml (len=345)

### saudibusiness.gov.sa
- Base: https://saudibusiness.gov.sa/ -> 200 (curl)
- Robots: https://saudibusiness.gov.sa/robots.txt -> 200 (curl)
- Sitemaps:
  - https://saudibusiness.gov.sa/sitemap.xml (common) -> 200 text/html; charset=utf-8 (len=247); non-XML content-type
  - https://saudibusiness.gov.sa/sitemap_index.xml (common) -> 200 text/html; charset=utf-8 (len=247); non-XML content-type
  - https://saudibusiness.gov.sa/sitemap-index.xml (common) -> 200 text/html; charset=utf-8 (len=247); non-XML content-type
  - https://saudibusiness.gov.sa/sitemap/sitemap.xml (common) -> 200 text/html; charset=utf-8 (len=247); non-XML content-type
  - https://saudibusiness.gov.sa/sitemap.xml.gz (common) -> 200 text/html; charset=utf-8 (len=247); non-XML content-type
  - https://saudibusiness.gov.sa/sitemap_index.xml.gz (common) -> 200 text/html; charset=utf-8 (len=247); non-XML content-type

### misa.gov.sa
- Base: https://misa.gov.sa/ -> 200 (curl)
- Robots: https://misa.gov.sa/robots.txt -> 200 (curl)
- Sitemaps:
  - https://misa.gov.sa/sitemap.xml (common) -> 200 text/xml; charset=UTF-8
  - https://misa.gov.sa/sitemap_index.xml (common) -> 200 text/xml; charset=UTF-8

### investsaudi.sa
- Base: https://investsaudi.sa/ -> 200 (curl)
- Robots: https://investsaudi.sa/robots.txt -> 200 (curl)
- Sitemaps:
  - https://investsaudi.sa/en/sitemap.xml (robots) -> 200 text/plain;charset=UTF-8 (len=128); non-XML content-type

### hrsd.gov.sa
- Base: https://hrsd.gov.sa/ -> 200 (curl)
- Robots: https://hrsd.gov.sa/robots.txt -> 200 (curl)
- Sitemaps:
  - https://www.hrsd.gov.sa/sitemap.xml (robots) -> 200 application/xml

### qiwa.sa
- Base: https://qiwa.sa/ar -> 200 (curl)
- Robots: https://qiwa.sa/robots.txt -> 200 (curl)
- Sitemaps:
  - https://qiwa.sa/sitemap.xml (common) -> 200 text/html (len=1351); non-XML content-type
  - https://qiwa.sa/sitemap_index.xml (common) -> 200 text/html (len=1351); non-XML content-type
  - https://qiwa.sa/sitemap-index.xml (common) -> 200 text/html (len=1351); non-XML content-type
  - https://qiwa.sa/sitemap/sitemap.xml (common) -> 200 text/html (len=1351); non-XML content-type
  - https://qiwa.sa/sitemap.xml.gz (common) -> 200 text/html (len=1351); non-XML content-type
  - https://qiwa.sa/sitemap_index.xml.gz (common) -> 200 text/html (len=1351); non-XML content-type

### muqeem.sa
- Base: https://muqeem.sa/ -> 200 (curl)
- Robots: https://muqeem.sa/robots.txt -> 200 (curl)
- Sitemaps:
  - https://muqeem.sa/sitemap.xml (common) -> 200 application/xml (len=413)
  - https://muqeem.sa/sitemap.xml.gz (common) -> 200 text/html; charset=utf-8 (len=1116); non-XML content-type
  - https://muqeem.sa/sitemap_index.xml.gz (common) -> 200 text/html; charset=utf-8 (len=1116); non-XML content-type

### absher.sa
- Base: https://absher.sa/wps/portal -> 302 (curl); final=https://www.absher.sa/portal/landing.html
- Robots: https://absher.sa/robots.txt -> 000 (curl)
- Sitemaps: none discovered
- Note: Deferred (login required). Base redirects to www.absher.sa; curl connection resets.

### www.moi.gov.sa
- Base: https://www.moi.gov.sa/wps/portal/Home/ -> 200 (curl)
- Robots: https://www.moi.gov.sa/robots.txt -> 200 (curl)
- Sitemaps: none discovered
- Note: No sitemap discovered via robots/common paths

### mudad.com.sa
- Base: https://mudad.com.sa/landing-page/home -> 200 (curl)
- Robots: https://mudad.com.sa/robots.txt -> 200 (curl)
- Sitemaps:
  - https://mudad.com.sa/sitemap.xml (robots) -> 200 application/xml (len=1292)

### rulebook.sama.gov.sa
- Base: https://rulebook.sama.gov.sa/ -> 200 (curl)
- Robots: https://rulebook.sama.gov.sa/robots.txt -> 200 (curl)
- Sitemaps:
  - https://www.sama.gov.sa/en-US/pages/sitemap.aspx (manual) -> 000; 200 via Playwright (curl reset)
- Note: No sitemap discovered via robots/common paths
- Note: Sitemap link found on homepage (requires Playwright).

### cma.org.sa
- Base: https://cma.org.sa/ -> 200 (curl)
- Robots: https://cma.org.sa/robots.txt -> 200 (curl)
- Sitemaps:
  - https://cma.gov.sa:443/sitemap.xml (robots) -> 200 text/xml (len=347)

### sdaia.gov.sa
- Base: https://sdaia.gov.sa/ -> 200 (curl)
- Robots: https://sdaia.gov.sa/robots.txt -> 200 (curl)
- Sitemaps:
  - https://sdaia.gov.sa/sitemap.xml (robots) -> 200 text/html (len=244); non-XML content-type

### nca.gov.sa
- Base: https://nca.gov.sa/ -> 200 (curl)
- Robots: https://nca.gov.sa/robots.txt -> 200 (curl)
- Sitemaps:
  - https://nca.gov.sa/sitemap.xml (robots) -> 200 application/xml

### gac.gov.sa
- Base: https://gac.gov.sa/ -> 200 (curl)
- Robots: https://gac.gov.sa/robots.txt -> 200 (curl)
- Sitemaps:
  - https://gac.gov.sa/sitemap.xml (common) -> 200 text/html (len=11540); non-XML content-type
  - https://gac.gov.sa/sitemap_index.xml (common) -> 200 text/html (len=11540); non-XML content-type
  - https://gac.gov.sa/sitemap-index.xml (common) -> 200 text/html (len=11540); non-XML content-type
  - https://gac.gov.sa/sitemap/sitemap.xml (common) -> 200 text/html (len=11540); non-XML content-type
  - https://gac.gov.sa/sitemap.xml.gz (common) -> 200 text/html (len=11540); non-XML content-type
  - https://gac.gov.sa/sitemap_index.xml.gz (common) -> 200 text/html (len=11540); non-XML content-type

### moj.gov.sa
- Base: https://moj.gov.sa/ -> 200 (curl)
- Robots: https://moj.gov.sa/robots.txt -> 200 (curl)
- Sitemaps:
  - https://moj.gov.sa/sitemap.xml (common) -> 200 text/xml (len=339)
  - https://moj.gov.sa/sitemap_index.xml (common) -> 200 text/html; charset=utf-8 (len=0); non-XML content-type
  - https://moj.gov.sa/sitemap/sitemap.xml (common) -> 200 text/html; charset=utf-8 (len=0); non-XML content-type
  - https://moj.gov.sa/sitemap.xml.gz (common) -> 200 text/html; charset=utf-8 (len=28015); non-XML content-type
  - https://moj.gov.sa/sitemap_index.xml.gz (common) -> 200 text/html; charset=utf-8 (len=28015); non-XML content-type

### najiz.sa
- Base: https://najiz.sa/ -> 200 (curl)
- Robots: https://najiz.sa/robots.txt -> 404 (curl)
- Sitemaps: none discovered
- Note: No sitemap discovered via robots/common paths

### safiu.gov.sa
- Base: https://safiu.gov.sa/ -> 000 (playwright)
- Robots: https://safiu.gov.sa/robots.txt -> 000 (playwright)
- Sitemaps: none discovered
- Note: Base unreachable via Playwright
- Note: robots.txt unreachable via Playwright
- Note: No sitemap discovered via robots/common paths

### momrah.gov.sa
- Base: https://momrah.gov.sa/ -> 200 (curl)
- Robots: https://momrah.gov.sa/robots.txt -> 200 (curl)
- Sitemaps:
  - https://momah.gov.sa/sitemap.xml (robots) -> 200 application/xml (len=221842)

### rega.gov.sa
- Base: https://rega.gov.sa/ -> 200 (playwright)
- Robots: https://rega.gov.sa/robots.txt -> 200 (playwright)
- Sitemaps: none discovered
- Note: No sitemap discovered via robots/common paths

### mim.gov.sa
- Base: https://mim.gov.sa/ -> 200 (curl)
- Robots: https://mim.gov.sa/robots.txt -> 200 (curl)
- Sitemaps:
  - https://www.mim.gov.sa/sitemap.xml (robots) -> 200 application/xml; charset=utf-8

### monshaat.gov.sa
- Base: https://monshaat.gov.sa/ -> 200 (curl)
- Robots: https://monshaat.gov.sa/robots.txt -> 200 (curl)
- Sitemaps:
  - https://monshaat.gov.sa/sitemap.xml (common) -> 200 text/xml; charset=utf-8

### modon.gov.sa
- Base: https://modon.gov.sa/ -> 200 (playwright); final=https://modon.gov.sa/ar/Pages/default.aspx
- Robots: https://modon.gov.sa/robots.txt -> 200 (playwright)
- Sitemaps:
  - https://modon.gov.sa:443/sitemap.xml (robots) -> 000

### tga.gov.sa
- Base: https://tga.gov.sa/ -> 200 (curl)
- Robots: https://tga.gov.sa/robots.txt -> 404 (curl)
- Sitemaps: none discovered
- Note: No sitemap discovered via robots/common paths

### gaca.gov.sa
- Base: https://gaca.gov.sa/ -> 200 (curl)
- Robots: https://gaca.gov.sa/robots.txt -> 200 (curl)
- Sitemaps:
  - https://gaca.gov.sa/sitemap.xml (robots) -> 200 text/xml (len=27958)

### mawani.gov.sa
- Base: https://mawani.gov.sa/ -> 200 (curl)
- Robots: https://mawani.gov.sa/robots.txt -> 200 (curl)
- Sitemaps:
  - https://mawani.gov.sa/sitemap.xml (common) -> 200 text/html (len=4363); non-XML content-type
  - https://mawani.gov.sa/sitemap_index.xml (common) -> 200 text/html (len=4363); non-XML content-type
  - https://mawani.gov.sa/sitemap-index.xml (common) -> 200 text/html (len=4363); non-XML content-type
  - https://mawani.gov.sa/sitemap/sitemap.xml (common) -> 200 text/html (len=4363); non-XML content-type
  - https://mawani.gov.sa/sitemap.xml.gz (common) -> 200 text/html (len=4363); non-XML content-type
  - https://mawani.gov.sa/sitemap_index.xml.gz (common) -> 200 text/html (len=4363); non-XML content-type

### mot.gov.sa
- Base: https://mot.gov.sa/ -> 200 (curl)
- Robots: https://mot.gov.sa/robots.txt -> 200 (curl)
- Sitemaps:
  - https://mot.gov.sa/sitemap.xml (robots) -> 200 text/xml;charset=UTF-8 (len=10702)

### moh.gov.sa
- Base: https://moh.gov.sa/ -> 200 (curl)
- Robots: https://moh.gov.sa/robots.txt -> 200 (curl)
- Sitemaps:
  - https://www.moh.gov.sa/SiteMap/sitemap.xml (robots) -> 200 text/xml (len=239930)
  - https://www.moh.gov.sa/en/SiteMap/sitemap.xml (robots) -> 200 text/xml (len=3284126)

### dga.gov.sa
- Base: https://dga.gov.sa/ -> 200 (curl)
- Robots: https://dga.gov.sa/robots.txt -> 200 (curl)
- Sitemaps: none discovered
- Note: No sitemap discovered via robots/common paths

### ndmo.gov.sa
- Base: https://ndmo.gov.sa/ -> 000 (playwright)
- Robots: https://ndmo.gov.sa/robots.txt -> 000 (playwright)
- Sitemaps: none discovered
- Note: Base unreachable via Playwright
- Note: robots.txt unreachable via Playwright
- Note: No sitemap discovered via robots/common paths

### mof.gov.sa
- Base: https://mof.gov.sa/ -> 200 (curl)
- Robots: https://mof.gov.sa/robots.txt -> 200 (curl)
- Sitemaps:
  - https://mof.gov.sa/sitemap.xml (common) -> 200 text/html (len=1596); non-XML content-type
  - https://mof.gov.sa/sitemap_index.xml (common) -> 200 text/html (len=1596); non-XML content-type
  - https://mof.gov.sa/sitemap-index.xml (common) -> 200 text/html (len=1596); non-XML content-type
  - https://mof.gov.sa/sitemap/sitemap.xml (common) -> 200 text/html (len=1596); non-XML content-type
  - https://mof.gov.sa/sitemap.xml.gz (common) -> 200 text/html; charset=utf-8 (len=1128); non-XML content-type
  - https://mof.gov.sa/sitemap_index.xml.gz (common) -> 200 text/html; charset=utf-8 (len=1129); non-XML content-type

### etimad.sa
- Base: https://etimad.sa/ -> 200 (curl)
- Robots: https://etimad.sa/robots.txt -> 200 (curl)
- Sitemaps:
  - https://etimad.sa/sitemap.xml (common) -> 200 text/html; charset=utf-8 (len=2222); non-XML content-type
  - https://etimad.sa/sitemap_index.xml (common) -> 200 text/html; charset=utf-8 (len=2222); non-XML content-type
  - https://etimad.sa/sitemap-index.xml (common) -> 200 text/html; charset=utf-8 (len=2222); non-XML content-type
  - https://etimad.sa/sitemap/sitemap.xml (common) -> 200 text/html; charset=utf-8 (len=2222); non-XML content-type
  - https://etimad.sa/sitemap.xml.gz (common) -> 200 text/html; charset=utf-8 (len=2222); non-XML content-type
  - https://etimad.sa/sitemap_index.xml.gz (common) -> 200 text/html; charset=utf-8 (len=2222); non-XML content-type

### cst.gov.sa
- Base: https://cst.gov.sa/ -> 200 (curl)
- Robots: https://cst.gov.sa/robots.txt -> 200 (curl)
- Sitemaps:
  - https://www.cst.gov.sa/api/sitemap (robots) -> 200 text/xml (len=3472663)

### www.sfda.gov.sa
- Base: https://www.sfda.gov.sa/ -> 200 (curl)
- Robots: https://www.sfda.gov.sa/robots.txt -> 200 (curl)
- Sitemaps:
  - https://www.sfda.gov.sa/sitemap.xml (common) -> 200 application/xml; charset=utf-8 (len=496703)

### saso.gov.sa
- Base: https://saso.gov.sa/ -> 200 (curl)
- Robots: https://saso.gov.sa/robots.txt -> 200 (curl)
- Sitemaps:
  - http://ryd1-exwb19-04:80/sitemap.xml (robots) -> 000
- Note: Playwright homepage navigation timed out; manual discovery needed.

### www.mewa.gov.sa
- Base: https://www.mewa.gov.sa/ -> 200 (curl)
- Robots: https://www.mewa.gov.sa/robots.txt -> 200 (curl)
- Sitemaps:
  - https://www.mewa.gov.sa/sitemap.xml (robots) -> 200 text/xml (len=357)

### saip.gov.sa
- Base: https://saip.gov.sa/ -> 200 (curl)
- Robots: https://saip.gov.sa/robots.txt -> 200 (curl)
- Sitemaps:
  - https://www.saip.gov.sa/sitemap.xml (robots) -> 200 application/xml (len=189)

### www.moenergy.gov.sa
- Base: https://www.moenergy.gov.sa/ -> 200 (curl)
- Robots: https://www.moenergy.gov.sa/robots.txt -> 200 (curl)
- Sitemaps:
  - https://www.moenergy.gov.sa/sitemap.xml (robots) -> 200 application/xml (len=133289)

### www.gcc-sg.org
- Base: https://www.gcc-sg.org/ -> 200 (curl)
- Robots: https://www.gcc-sg.org/robots.txt -> 200 (curl)
- Sitemaps:
  - https://www.gcc-sg.org/sitemap.xml (common) -> 200 text/html (len=2817); non-XML content-type
  - https://www.gcc-sg.org/sitemap_index.xml (common) -> 200 text/html (len=2817); non-XML content-type
  - https://www.gcc-sg.org/sitemap-index.xml (common) -> 200 text/html (len=2817); non-XML content-type
  - https://www.gcc-sg.org/sitemap/sitemap.xml (common) -> 200 text/html (len=2817); non-XML content-type
  - https://www.gcc-sg.org/sitemap.xml.gz (common) -> 200 text/html (len=2817); non-XML content-type
  - https://www.gcc-sg.org/sitemap_index.xml.gz (common) -> 200 text/html (len=2817); non-XML content-type

### www.oecd.org
- Base: https://www.oecd.org/ -> 200 (playwright)
- Robots: https://www.oecd.org/robots.txt -> 200 (curl)
- Sitemaps:
  - https://www.oecd.org/sitemap.xml (robots) -> 403 text/html; charset=UTF-8 (len=7010); 200 via Playwright (curl 403), non-XML content-type

### data.gov.sa
- Base: https://data.gov.sa/ -> 200 (curl)
- Robots: https://data.gov.sa/robots.txt -> 200 (curl)
- Sitemaps:
  - https://data.gov.sa/sitemap.xml (robots) -> 200 text/html; charset=utf-8 (len=247); non-XML content-type

### my.gov.sa
- Base: https://my.gov.sa/ -> 200 (playwright); final=https://my.gov.sa/ar
- Robots: https://my.gov.sa/robots.txt -> 200 (playwright)
- Sitemaps:
  - https://my.gov.sa/sitemap.xml (robots) -> 403 text/html; charset=UTF-8; 200 via Playwright (curl 403), non-XML content-type

## Blockers / Gaps
- `api.gov.sa`: NXDOMAIN from public resolvers. User confirmed it is covered by `my.gov.sa`.
- `my.gov.sa`: curl blocked (403) for base/robots/sitemap; Playwright succeeds. Crawler must use browser fetch for this host.
- `www.oecd.org`: curl blocked (403) for base/sitemap; Playwright succeeds. Crawler must use browser fetch for this host.
- `absher.sa`: login required; curl resets against www.absher.sa. Deferred.
- `boe.gov.sa` and `laws.boe.gov.sa`: TLS requires DigiCert intermediate; sitemap endpoints return HTML/404 (no valid XML sitemap). Manual discovery needed or alternative sitemap endpoints.
- `uqn.gov.sa`: deferred (newspaper); robots.txt missing (404) and no sitemap found on common paths.
- `rulebook.sama.gov.sa`: sitemap link points to `https://www.sama.gov.sa/en-US/pages/sitemap.aspx` (Playwright-only). Manual extraction required.
- `saso.gov.sa`: robots sitemap points to an internal host (ryd1-exwb19-04) and Playwright times out. Need an external sitemap or listing path.
- Non-XML sitemap candidates (manual discovery likely required): `ncar.gov.sa`, `saudibusiness.gov.sa`, `sdaia.gov.sa`, `data.gov.sa`, `www.gcc-sg.org`.