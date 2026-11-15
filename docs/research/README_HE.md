# מחקר CamScanner מהקו הישן של DocScan

שני המסמכים כאן הועתקו כמו שהם, ללא שינוי, מהענף הראשי הישן (`main`, ‏commit `e4b6a8c8`, ‏DocScan v0.9.0 מ־5 בנובמבר 2026). ההעתקה נעשתה כשהענף `feature/original-filters` הועבר להיות `main`, ב־15 בנובמבר 2026, לבקשת בעל הפרויקט. ההיסטוריה המלאה שלהם נשמרת גם ב־git.

| מסמך | מה יש בו |
| --- | --- |
| [`CAMSCANNER_PACKAGE_ANALYSIS_HE.md`](CAMSCANNER_PACKAGE_ANALYSIS_HE.md) | **ניתוח סטטי של חבילת CamScanner ‏7.28.0** (APKS): מנוע הסריקה ב־native, זיהוי מ־YUV, הספריות `scannercs`, ‏`magicenhancer`, ‏`imageprocessor` ומנועי רשת, ומשפחות המסננים. |
| [`CAMSCANNER_FEATURE_AUDIT_HE.md`](CAMSCANNER_FEATURE_AUDIT_HE.md) | **פערי יכולות בין DocScan v0.6 הישן ל־CamScanner**, לפי נושאים, עם מקורות ותוכנית עדיפויות. נכון לאותה גרסה. רוב הפערים נסגרו מאז ב־DocScan Scanner ‏1.x. |

**קישורים שבורים:** חלק מהקישורים הפנימיים במסמכים מפנים לקובצי MILESTONE של הקו הישן, ואלה לא הועתקו.

**שאר המחקר:** כלי המחקר וגרסאות ה־Native נמצאים בענף `rebuild/camscanner-reference`, בתיקייה `research/` שלו.
