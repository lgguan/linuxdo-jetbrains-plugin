// Run with playwright-cli run-code --filename tools/navigation-regression.js.
async (page) => {
    const origin = 'http://127.0.0.1:8765/navigation-fixture.html';
    const results = [];
    const check = (name, pass) => { if (!pass) throw new Error(name); results.push(name); };
    await page.setViewportSize({width:1155,height:650});
    await page.goto(origin);
    await page.getByRole('textbox', {name:'跳转到楼层'}).fill('6386');
    await page.getByRole('button', {name:'跳转', exact:true}).click();
    await page.waitForFunction(() => !!document.querySelector('[data-post-number="6386"]'));
    await page.waitForTimeout(1600);
    check('tail jump performs one request and does not backfill 6000 floors', await page.evaluate(() => calls.length===1 && calls[0].type==='jump'));
    check('last reply remains above navigation', await page.evaluate(() => document.querySelector('[data-post-number="6386"]').getBoundingClientRect().bottom <= document.querySelector('.topic-navigation').getBoundingClientRect().top));
    await page.screenshot({path:'output/playwright/navigation-dark.png'});
    await page.evaluate(() => linuxDoPagination.jump(6380));
    check('loaded floor navigation is local', await page.evaluate(() => calls.length===1));
    await page.mouse.move(500,300);
    await page.mouse.wheel(0,2000);
    await page.waitForTimeout(300);
    check('scroll at tail does not fetch middle gaps', await page.evaluate(() => calls.length===1));

    await page.evaluate(() => { hold=true; linuxDoPagination.jump(4000); linuxDoPagination.jump(4200); linuxDoPagination.jump(4300); });
    await page.waitForFunction(() => calls.filter(c=>c.type==='jump').length===2);
    await page.evaluate(() => respondJump(calls[calls.length-1]));
    await page.waitForFunction(() => calls.filter(c=>c.type==='jump').length===3);
    check('rapid jumps keep only the latest queued floor and are spaced', await page.evaluate(() => calls[2].floor===4300 && calls[2].time-calls[1].time>=780));
    await page.evaluate(() => { respondJump(calls[2]); hold=false; });
    await page.waitForTimeout(100);
    check('latest target wins', await page.evaluate(() => document.querySelector('#floor-progress').value==='4300'));
    await page.evaluate(() => linuxDoPagination.jumped('stale','1',allIds,fragment(['5555']),false,6386));
    check('stale response is ignored', await page.evaluate(() => !document.querySelector('[data-post-number="5555"]')));

    await page.evaluate(() => { failNext=3; linuxDoPagination.jump(5000); });
    await page.waitForFunction(() => document.querySelector('.topic-navigation').classList.contains('is-cooling-down'));
    const count = await page.evaluate(() => calls.length);
    await page.evaluate(() => { linuxDoPagination.jump(5100); linuxDoPagination.refresh(); dispatchEvent(new Event('scroll')); });
    await page.waitForTimeout(100);
    check('429 blocks jump refresh and prefetch', await page.evaluate(n => calls.length===n,count));
    await page.getByRole('textbox', {name:'跳转到楼层'}).fill('6386');
    await page.getByRole('button', {name:'跳转',exact:true}).click();
    check('429 still allows local cached navigation', await page.evaluate(() => document.querySelector('#floor-progress').value==='6386'));
    await page.screenshot({path:'output/playwright/navigation-cooldown.png'});
    await page.waitForFunction(() => !document.querySelector('.topic-navigation').classList.contains('is-cooling-down'));
    check('cooldown does not automatically replay requests', await page.evaluate(n => calls.length===n,count));

    await page.goto(origin+'?start=500');
    await page.waitForFunction(() => calls.length===1 && !!document.querySelector('[data-post-number="480"]'));
    await page.waitForTimeout(300);
    check('one adjacent upward batch without response-driven chaining', await page.evaluate(() => calls.length===1 && calls[0].ids[0]==='480' && calls[0].ids.length===20));
    await page.mouse.move(500,300);
    await page.mouse.wheel(0,10000);
    await page.waitForFunction(() => calls.length===2);
    check('downward batch starts after the loaded edge', await page.evaluate(() => calls[1].direction==='after' && calls[1].ids[0]==='520'));

    await page.goto(origin+'?light');
    await page.setViewportSize({width:390,height:700});
    await page.getByRole('textbox', {name:'跳转到楼层'}).fill('6386');
    await page.getByRole('button', {name:'跳转',exact:true}).click();
    await page.waitForFunction(() => !!document.querySelector('[data-post-number="6386"]'));
    check('narrow layout has no horizontal overflow', await page.evaluate(() => document.documentElement.scrollWidth<=innerWidth && [...document.querySelectorAll('.topic-navigation button,.topic-navigation input')].every(el => {const r=el.getBoundingClientRect();return r.left>=0 && r.right<=innerWidth;})));
    await page.screenshot({path:'output/playwright/navigation-light-narrow.png'});
    console.log(JSON.stringify({passed:results.length,results}));
}
