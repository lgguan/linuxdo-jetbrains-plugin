"""Read-only notification contract check in an existing dedicated Chrome session.

Saves field presence, counts and notification type IDs only; no content or credentials.
Never visits recent notifications (which can update seen state), logs in or marks read.
"""
import argparse
import json
from pathlib import Path
from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--endpoint', default='http://127.0.0.1:19337')
args = parser.parse_args()
output = ROOT / 'build/notification-reference'
output.mkdir(parents=True, exist_ok=True)
try:
    with sync_playwright() as playwright:
        browser = playwright.chromium.connect_over_cdp(args.endpoint, timeout=5000)
        pages = [p for c in browser.contexts for p in c.pages if p.url.startswith('https://linux.do/')]
        assert pages, 'No existing forum page; no new page or login is opened.'
        result = pages[0].evaluate('''async () => {
            const result = {writes:0,requests:[]};
            for(const path of ['/site.json','/notifications.json?offset=0&limit=30','/notifications/totals.json','/notifications.json?offset=0&limit=30&filter=unread','/notifications.json?offset=0&limit=30&filter=read','/notifications.json?offset=30&limit=30']) {
                const response = await fetch(path,{method:'GET',credentials:'include',headers:{Accept:'application/json'}});
                const entry = {path,status:response.status}; result.requests.push(entry);
                if(!response.ok) break;
                const data = await response.json();
                if(path==='/site.json') entry.notificationTypes=data.notification_types || null;
                else if(path.includes('totals')) {
                    entry.hasOrdinaryCount=Number.isInteger(data.unread_notifications);
                    entry.hasPersonalMessageCount=Number.isInteger(data.unread_personal_messages);
                    entry.ordinary=data.unread_notifications; entry.personalMessages=data.unread_personal_messages;
                } else {
                    entry.rows=data.notifications?.length; entry.total=data.total_rows_notifications;
                    entry.allRead=(data.notifications || []).every(n=>n.read===true);
                    entry.allUnread=(data.notifications || []).every(n=>n.read===false);
                    entry.hasSeenId=Object.hasOwn(data,'seen_notification_id');
                    entry.hasContinuation=typeof data.load_more_notifications==='string';
                    entry.types=[...new Set((data.notifications || []).map(n=>n.notification_type))];
                    entry.hasTopicTarget=(data.notifications || []).some(n=>Number.isInteger(n.topic_id));
                    entry.hasFloorTarget=(data.notifications || []).some(n=>Number.isInteger(n.post_number));
                }
            }
            return result;
        }''')
except Exception as error:
    result = {'writes': 0, 'available': False, 'reason': type(error).__name__}
(output / 'linuxdo-notifications.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(result, ensure_ascii=False))
