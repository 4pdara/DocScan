# המסננים, היישור ומצבי הצילום של CamScanner, והשוואה ל־DocScan

**מקורות:** קוד ה־Java של 7.28.5 מ־Google Play (מפורק ב־JADX), מחרוזות הממשק באנגלית מתוך `resources.arsc`, והספריות native של 7.29.0. אלה זהות ל־7.28.5 בספריות הזיהוי והמסננים, ראו [`CAMSCANNER_7.29_DETECTION_HE.md`](CAMSCANNER_7.29_DETECTION_HE.md). שום קוד, משאב או מודל של CamScanner לא נכנס לריפו.

## המסננים
הרשימה נקראה מ־`FilterThumbResource.Type`, מ־`MultiEnhanceModel` ומ־`ScannerUtils.ENHANCE_*`. מי מריץ כל מסנן נקבע לפי `ScannerUtils.enhanceImageFile` ו־`isServerEnhanceMode`.

| מסנן (שם בממשק) | מצב מנוע | מי מריץ | אצלנו |
| --- | --- | --- | --- |
| Original | ‏`-1` | ללא עיבוד | **מקור** |
| Lighten ("brighten") | ‏`15` (‏LINEAR) | `libscannercs`, ללא רשת | **הבהרה** |
| Magic Color / Enhance | ‏`17` (‏MAGIC) | `libscannercs` (‏`EnhanceColorImageByModes`, ואחרי `DetectColorImageMode`), ללא רשת | **צבע קסם** |
| Grayscale | ‏`10` | `libscannercs` | **אפור** |
| B&W | ‏`19` | `libscannercs` | **שחור־לבן** |
| Eco ("save ink") | ‏`16` (‏WHITE_BLACK) | `libscannercs` | **חיסכון בדיו** |
| Invert | ‏`110` | `libscannercs` | **היפוך צבעים** |
| No Shadow | ‏`-10`, ‏`-11` | **רשת** בטלפון: `MagicEnhancer.deshadow…`. המודל מוצפן | **ללא צללים**, בלי רשת |
| Magic Pro (סופר פילטר) | ‏`-12` | **שרת**, או רשת בטלפון כשזמינה (`SuperFilterEngine.isAllowLocalSuperFilter`). יש מכסה יומית | **מקצועי**, בלי רשת |
| AI Magic / Omnifix | סופר פילטר | **שרת**, דורש סנכרון לענן. לפי התיאור שלהם: מסיר אצבעות ומוארה, מיישר דפים מעוקלים | אין |
| No Watermark | ‏`8` | **שרת** | אין |
| No Handwriting | ‏`9` | **שרת** ("CS AI is removing handwriting") | חלקי: מחיקת סימונים בצבע |
| Remove Glare | ‏`124` | **שרת**, דורש אינטרנט, מכסה יומית | אין |
| לוח / נייר / טבלה | ‏`118` עד `123` | `libscannercs`. מצב `122` ("white pad") בשרת | **לוח, לוח לנייר, לוח הפוך** |

**התאמות:** בהירות, ניגודיות ו"פרטים" (`ScannerEngine.adjustImageS(..., brightness-50, contrast-50, detail)`). אצלנו יש בהירות, ניגודיות, רוויה וחדות.

**ממצא מרכזי:** המסננים הבסיסיים שלהם, ובהם צבע קסם, הם אלגוריתמים רגילים שרצים בטלפון. כל מה שמסיר עצמים (אצבעות, כתב יד, השתקפות, מוארה, סימן מים) רץ ברשת מוצפנת או בשרת שלהם.

## היישור
| דרך | איפה | איך |
| --- | --- | --- |
| יישור בתוך החיתוך | `CropDewarpImagePos…` (‏`libpagescanner`) | רשת יישור (240×240) מחזירה שדה הזזה בשני ערוצים, x ו־y, והתמונה ממופה מחדש לפיו. האפשרות נשלטת מהשרת (`cut_optimization_688`). |
| חיתוך לפי גבול מעוקל | `CurveCropDewarpImagePos…`, ‏`DetectCurveBorder` | רשת מסמנת את הדף, והגבול מתואר ב־20 נקודות לכל צלע. אחרי התאמת עקומות (Catmull-Rom) הדף נמתח למלבן. |
| "Correcting Curved Pages" | הגדרה בממשק ("Recognize and correct for curved pages") | הפעלה של מה שמעל. |
| AI Magic | שרת | בין השאר "straightens curved pages". |

**אצלנו (DocScan):** יישור אוטומטי לפי שורות הטקסט (משטח B-spline של 16×6), ויישור קימור ידני. אין חיתוך לפי גבול מעוקל.

## מצבי צילום (`CaptureMode`)
‏NORMAL (יחיד, קבוצה, workbench), ‏BOOK_SPLITTER, ‏PPT, ‏WHITE_PAD, ‏WRITING_PAD, ‏CERTIFICATE (תעודות), ‏CERTIFICATE_PHOTO (תמונת פספורט), ‏NAMECARD, ‏BARCODE, ‏OCR, ‏DOC_TO_WORD, ‏DOC_TO_EXCEL, ‏DOC_TO_PPT, ‏TRANSLATE, ‏FORMULA, ‏TOPIC (דף מבחן), ‏LARGE_SCAN (חיבור כמה צילומים לאחד), ‏SMART_ERASE, ‏IMAGE_RESTORE, ‏HD_IMAGE, ‏MARK_CAM (מצלמה עם חותמת), ‏SIGNATURE, ‏INVOICE, ‏RECEIPT, ‏BANK_CARD_JOURNAL, ‏COUNT_NUMBER, ‏E_EVIDENCE, ‏GREET_CARD, ומצבי AI (‏CS_AI_SCAN, ‏AI_SOLVER_…).

**כבר יש אצלנו:** ספר, לוח, שקופיות, חותמת זמן, תעודה מזהה, תמונות פספורט, QR וברקודים, זיהוי טקסט, ייצוא ל־Word, ל־Excel ול־PowerPoint, חתימה ומחיקה חכמה.

## מה אפשר לממש אצלנו, בלי רשת ובלי שרת
המימוש מקורי, לפי סדר התועלת המשוער:
1. **חיתוך לפי גבול מעוקל:** איתור הקצה העליון והתחתון המעוקלים של דף בספר, ומיפוי שלו למלבן (משטח Coons). זה משלים את היישור לפי שורות טקסט, ועוזר גם כשאין מספיק טקסט.
2. **הסרת השתקפות בסיסית:** איתור כתמי ברק (בהירים מאוד ובלי צבע) והשלמה שלהם מהרקע שמסביב.
3. **הסרת אצבעות בשולי הדף:** איתור אזורים בצבע עור שנוגעים בקצה הדף, ומילוי שלהם בצבע הנייר.
4. **הסרת מוארה בסיסית:** סינון בתדר לצילום מסך.
5. **התאמת "פרטים" (חדות)**, אם חסרה בממשק.
6. **צילום ארוך:** חיבור כמה צילומים של מסמך ארוך לתמונה אחת.

**מה לא יגיע לרמה שלהם בלי רשת משלנו:** הסרת כתב יד, הסרת סימני מים, AI Magic, זיהוי גבולות ויישור ברמה שלהם. לכל אלה נדרשת רשת מאומנת משלנו, ראו את התוכנית ב־[`CAMSCANNER_7.29_DETECTION_HE.md`](CAMSCANNER_7.29_DETECTION_HE.md).
