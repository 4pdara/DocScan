# בדיקת מנוע CamScanner דרך JNI ו־Frida
5 בנובמבר 2026. ענף `rebuild/camscanner-reference`. מסמך זה מתעד בדיקה בתהליך; המנוע אינו משולב עדיין ביישום הייצור.

## קלט וכלים
נבדקה ההעלאה `.apks.zip` בגודל 252,579,947 בתים, SHA256:
`35777d7072db578bbd1c38ac955faed90afa6fe64a08edc07b1cbd5e6108ddb4`.
זהו hash של המכל בשם ZIP. ההעלאה המקורית בשם APKS משתמשת במכל עם hash אחר; בדוח הקודם אומתה זהות תוכן 27 רכיבי APK. אין לבלבל בין hash המכלים.
הקלט והקוד המפורק נשארים מחוץ ל־Git.

בוצעו מחדש חילוץ, מיפוי DEX ו־ELF, שחזור ScannerEngine ו־MagicEnhancer ב־JADX 1.5.6, וניתוח Ghidra 11.4.2 של libscannercs, libmagicenhancer, libpagescanner ו־libimageprocessor. בניתוח Ghidra הוחלו גם טיפוסי הפרמטרים וההחזרה מהצהרות JNI שנמצאו ב־DEX; פסאודו־קוד עדיין אינו מקור מקורי או הוכחת הרצה.

| מחלקה | הצהרות native שנמצאו |
| --- | ---: |
| ScannerEngine | 40 |
| MagicEnhancer | 36 |
| PageScanner | 102 |
| ImageProcessor | 24 |

נמצאו 68 ספריות ARM64 ברכיב ABI. התלויות הישירות של scannercs כוללות mainstone וספריות מערכת; מסלולי Magic/Page/ImageProcessor משתמשים גם ב־OpenCV של החבילה, MNN ו/או ncnn ו־CVNN. אין להשתמש ב־Mat פנימי של ספרייה אחת כקלט לספריית OpenCV אחרת.

## ממצאי אתחול ופורמטים
- initEngine מקבל Context ומחרוזת, קורא verifyKeyV2 ושומר את תוצאתו במצב המנוע. initThreadContext בודק שהמצב הוא 0 לפני יצירת הקשר; ערך המצב ההתחלתי בבינארי הוא ‎-1. אין בבדיקה שינוי של תוצאת האימות, חיפוש/העתקת מפתחות הפעלה או התחזות לזהות חבילה.
- ScannerEngine משתמש במזהים של משאבי native ולא בכתובות Java. בקוד שנבדק יש טבלת 256 משאבים. יש לשחרר תמונה והקשר באמצעות הפונקציה המתאימה, ואסור להתייחס לכל int שחוזר כהצלחה.
- enhanceBitmap מעבד Bitmap דרך AndroidBitmap_getInfo/lockPixels/unlockPixels. נבדקו סטטית מסלולי ARGB_8888 ו־RGB_565; טיפוסי ההחזרה ותנאי הקלט דורשים אימות בהרצה.
- מיפוי המצבים מהצהרות המחלקה: אפור 10, צבע 13, שחור־לבן 19 ו־Magic 17. זהו מיפוי ממשק, לא טענה לאיכות או לזמינות.
- ב־MagicEnhancer נמצאו אתחולי מודלים וקריאות עיבוד נפרדים. initDeshadowModelNew מפנה ל־scanet::init_model; בחלק מהאתחולים נמצאו קריאות שמקבלות אזורי נתונים מתוך הבינארי. אין בכך הוכחה שכל המודלים או כל המצבים פועלים ללא משאבים נוספים.

## בדיקת JNI שהוכנה
נבנתה מקומית אפליקציית Debug קטנה בשם `org.docscan.engineprobe`, ללא רשת ועם אחסון נפרד. ההצהרות בה נוצרו מחתימות DEX, והמימוש הבוחן נכתב מחדש. הספריות הועתקו רק לחבילת הבדיקה המקומית; הן אינן במאגר.

הבדיקה מתוכננת למדוד טעינה וגרסאות, אתחול רגיל עם מחרוזת רישוי ריקה, יצירת הקשר, שינוי תמונת בדיקה, גודל הפלט, hash ושחרור משאבים. כשל באתחול המנוע הבסיסי עוצר את מסלול העיבוד שלו. מסלול המודלים הנפרד של Magic נבדק באמצעות ממשק האתחול הרגיל שלו.

## מצב הרצה
הורדו והותקנו כלי Frida תואמים, גרסה 17.25.2. script התצפית ב־`research/observe_engine.js` שומר את ערכי ההחזרה המקוריים ומסתיר מחרוזות בפרמטרים. עצם קיום script אינו בדיקה שעברה.

ניסיון אמולטור ARM64 ישיר הגיע לחיבור ADB, אך לא השלים סביבת בדיקה תקינה; תועדו תקלות תאימות באמולטורים 30.1.5 ו־32.1.15. נפתח ניסיון Android 11 x86_64 עם תמיכת תרגום ARM לפי תיעוד Google. כרגע אין עדיין תוצאת JNI, הפעלת CamScanner עם Frida או תמונת פלט שניתן להציג כהצלחה. התוצאות יעודכנו לאחר סיום ההרצה.

## כלי שחזור
- `research/prepare_reference.py`: חילוץ פרטי ורשימת תלויות/סמלים.
- `research/dex_signatures.py`: מיפוי מחלקות המוגדרות בפועל ב־DEX וחתימותיהן, בשונה מהופעת שם כמחרוזת הפניה.
- `research/NativeBoundary.py`: script ל־Ghidra לפענוח ממוקד והחלת חתימות.
- `research/build_probe.py` ו־`research/probe/src/org/docscan/engineprobe/ProbeActivity.java`: יצירת חבילת הבדיקה המקומית.
- `research/observe_engine.js`: תצפית ב־JNI/Java בזמן ריצה; אינו משנה תוצאות אימות.

הסקריפטים דורשים סביבת מחקר מקומית ותלויות ואינם חלק מבניית DocScan הרגילה. תיקיית `runtime` מכילה SDK/JDK מקומיים; אין בה קוד מהיישום הישן. הרצת הבדיקה אינה מתקינה או מסירה את DocScan.

מקורות לתשתית: [Frida Android](https://frida.re/docs/android/), [Frida JavaScript API](https://frida.re/docs/javascript-api/), [הערות Google על תמיכת ARM בתמונות Android 11](https://developer.android.com/studio/releases/emulator), [ארכיון אמולטורים](https://developer.android.com/studio/emulator_archive).
