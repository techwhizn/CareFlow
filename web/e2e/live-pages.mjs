import assert from 'node:assert/strict';
import {chromium} from 'playwright-core';
const token=process.env.CAREFLOW_TOKEN;
const url=process.env.CAREFLOW_URL;
if (!token || !url) throw Error('Set CAREFLOW_URL and CAREFLOW_TOKEN for a synthetic owner workspace');
const browser=await chromium.launch({headless:true,...(process.env.CHROME_EXECUTABLE?{executablePath:process.env.CHROME_EXECUTABLE}:{})});
try {
 const page=await browser.newPage({viewport:{width:390,height:844}});
 const errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.goto(url);
 await page.getByLabel('访问凭证',{exact:true}).fill(token);
 await page.keyboard.press('Tab');await page.keyboard.press('Enter');
 await page.getByRole('button',{name:'知识库',exact:true}).waitFor();
 const rows=[];
 for (const name of ['知识库','任务中心','测试集与评审','检索调试','引用问答','知识改进','应用中心','套餐与用量','成员与权限','模型配置','审计日志','运行状态']) {
  await page.getByRole('button',{name,exact:true}).click();
  await page.waitForFunction(()=>!document.querySelector('.loading'));
  const width=await page.evaluate(()=>document.documentElement.scrollWidth);
  rows.push({page:name,viewport:390,width});

 }
 await page.getByRole('button',{name:'知识库',exact:true}).click();
 await page.getByRole('button',{name:'创建知识库',exact:true}).click();
 const dialog=page.getByRole('dialog');
 assert.equal(await dialog.evaluate(el=>el.contains(document.activeElement)),true);
 await page.keyboard.press('Escape');await dialog.waitFor({state:'detached'});
 await page.getByRole('button',{name:'工作台',exact:true}).click();
 await page.route('**/api/v1/knowledge-bases',route=>route.fulfill({status:503,contentType:'application/json',body:JSON.stringify({code:'SYNTHETIC_UI_FAILURE',message:'Synthetic browser error-state fixture'})}));
 await page.getByRole('button',{name:'知识库',exact:true}).click();
 await page.getByRole('alert').filter({hasText:'Synthetic browser error-state fixture'}).waitFor();
 await page.unroute('**/api/v1/knowledge-bases');
 assert.ok(rows.every(row=>row.width<=row.viewport),'A page overflows the viewport');
 assert.deepEqual(errors,[]);
 console.log(JSON.stringify({browser:browser.version(),node:process.version,rows,errors,keyboard_login:true,dialog_keyboard:true,error_state:'Controlled browser-only 503 injection; alert displayed'}));
} finally {await browser.close();}
