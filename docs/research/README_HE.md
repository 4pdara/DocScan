# מחקר CamScanner מהקו הישן של DocScan

שני המסמכים כאן הועתקו כמו שהם, ללא שינוי, מהענף הראשי הישן (`main`, ‏commit `e4b6a8c8`, ‏DocScan v0.9.0 מ־5 בנובמבר 2026). ההעתקה נעשתה כשהענף `feature/original-filters` הועבר להיות `main`, ב־15 בנובמבר 2026, לבקשת בעל הפרויקט. ההיסטוריה המלאה שלהם נשמרת גם ב־git.

| מסמך | מה יש בו |
| --- | --- |
| [`CAMSCANNER_PACKAGE_ANALYSIS_HE.md`](CAMSCANNER_PACKAGE_ANALYSIS_HE.md) | **ניתוח סטטי של חבילת CamScanner ‏7.28.0** (APKS): מנוע הסריקה ב־native, זיהוי מ־YUV, הספריות `scannercs`, ‏`magicenhancer`, ‏`imageprocessor` ומנועי רשת, ומשפחות המסננים. |
| [`CAMSCANNER_7.29_DETECTION_HE.md`](CAMSCANNER_7.29_DETECTION_HE.md) | **איך CamScanner ‏7.29 מזהה, חותך ומיישר** (16 בנובמבר 2026): שני מנועי זיהוי, חמש רשתות MNN מוטמעות (240×240 ו־YOLOv5 ‏320×320), ושלבי העיבוד לפי שמות הפונקציות. נוסף אחרי ההעתקה. |
| [`CAMSCANNER_FILTERS_OPTIONS_HE.md`](CAMSCANNER_FILTERS_OPTIONS_HE.md) | **כל המסננים, דרכי היישור ומצבי הצילום של CamScanner** (7.28.5 ו־7.29): מה רץ בטלפון ומה ברשת או בשרת, השוואה ל־DocScan, ומה אפשר לממש אצלנו. נוסף אחרי ההעתקה. |
| [`CAMSCANNER_FEATURE_AUDIT_HE.md`](CAMSCANNER_FEATURE_AUDIT_HE.md) | **פערי יכולות בין DocScan v0.6 הישן ל־CamScanner**, לפי נושאים, עם מקורות ותוכנית עדיפויות. נכון לאותה גרסה. רוב הפערים נסגרו מאז ב־DocScan Scanner ‏1.x. |

**קישורים שבורים:** חלק מהקישורים הפנימיים במסמכים מפנים לקובצי MILESTONE של הקו הישן, ואלה לא הועתקו.

**שאר המחקר:** כלי המחקר וגרסאות ה־Native נמצאים בענף `rebuild/camscanner-reference`, בתיקייה `research/` שלו.
