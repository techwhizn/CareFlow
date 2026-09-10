import assert from 'node:assert/strict';
import {chromium} from 'playwright-core';
import {createServer} from 'vite';

const server=await createServer({server:{host:'127.0.0.1',port:4177,strictPort:true}});
await server.listen();
let browser;
try {
  browser=await chromium.launch({headless:true,...(process.env.CHROME_EXECUTABLE ? {executablePath:process.env.CHROME_EXECUTABLE}: {})});
  const page=await browser.newPage({viewport:{width:390,height:844}});
  const errors=[];
  page.on('pageerror',error=>errors.push(error.message));
  // The fixture uses the production component, without API calls or credentials.
  await page.goto(server.resolvedUrls.local[0]+'e2e/dialog.html');
  const opener=page.getByRole('button',{name:'打开测试弹窗'});
  await opener.focus();
  await page.keyboard.press('Enter');
  const dialog=page.getByRole('dialog',{name:'键盘回归测试'});
  await dialog.waitFor();
  assert.equal(await dialog.evaluate(el=>el.contains(document.activeElement)),true);
  await page.getByLabel('测试名称').fill('合成键盘输入');
  for (let i=0;i<6;i++) {
    await page.keyboard.press('Tab');
    assert.equal(await page.getByRole('button',{name:'背景操作'}).evaluate(el=>el===document.activeElement),false);
  }
  await page.keyboard.press('Escape');
  await dialog.waitFor({state:'detached'});
  assert.equal(await opener.evaluate(el=>el===document.activeElement),true);
  await page.keyboard.press('Enter');
  await dialog.waitFor();
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
  const bounds=await dialog.boundingBox();
  assert.ok(bounds.x>=0 && bounds.x+bounds.width<=390);
  await page.mouse.click(2,2);
  await dialog.waitFor({state:'detached'});
  assert.equal(await opener.evaluate(el=>el===document.activeElement),true);
  assert.deepEqual(errors,[]);
  console.log('PASS dialog focus entry, background isolation, Escape, focus return, backdrop and narrow viewport');
} finally {
  await browser?.close();
  await server.close();
}
