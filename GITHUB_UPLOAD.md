# الرفع على GitHub — خطوات جاهزة / GitHub upload — ready steps

**الحالة الآن (2026-11-23): المستودع موجود والربط متحقَّق منه.**
المستودع **`kitchen-designer-muse`** موجود على حساب **Abdfaith82**
(أُنشئ 2026-11-22) ويحتوي حاليًا على ملفين فقط: `README.md` و`test.txt`.
حساب GitHub مربوط بـMuse وتم التحقق من الوصول فعليًا في 2026-11-22 23:04
(قراءة المستودعات سليمة). الرابط:
`https://github.com/Abdfaith82/kitchen-designer-muse`

المشروع في هذه النسخة هو **v0.4.0** (102 ملف، اختبارات Kotlin ‏162/162
وPython ‏24/24)، والملف المضغوط الجاهز للرفع هو
`kitchen-designer-android-v0.4.0.zip` (وليس ملف v0.2.0 القديم).
المجلد `kitchen-designer-android` نفسه ليس مستودع git بعد، فالرفع يتم
بإحدى الطريقتين أدناه.

## الطريقة الأسرع — من موقع GitHub (بدون أوامر)

1. افتح مستودعك: https://github.com/Abdfaith82/kitchen-designer-muse
2. اضغط **Add file → Upload files** وارفع ملف ZIP
   `kitchen-designer-android-v0.4.0.zip` (أو ارفع محتوياته بعد فك ضغطه)،
   ثم Commit changes.
3. بعد أول push، افتح تبويب **Actions** — سيعمل سير العمل
   `.github/workflows/android.yml` تلقائيًا ويبني APK تجريبي
   (debug) ويرفعه كـ Artifact باسم `app-debug-apk`.

## الطريقة الثانية — من سطر الأوامر

```bash
cd kitchen-designer-android
git init
git add .
git commit -m "Kitchen Designer Android v0.4.0"
git branch -M main
git remote add origin https://github.com/Abdfaith82/kitchen-designer-muse.git
git push -u origin main
```

أو اطلب من Muse رفع المشروع مباشرة إلى `kitchen-designer-muse` —
الوصول متحقَّق منه، والرفع يحتاج موافقتك فقط.

## ملاحظات مهمة قبل البناء

- **APK مبني محليًا بالفعل** (2026-11-22): `app-debug.apk` ‏25.6MB،
  الحزمة `com.kitchendesigner`، ‏minSdk 29 / targetSdk 35،
  SHA-256 ‏`cd9a63df189fa2c7653705f5e7cd052c523a86e85da4a280fd6097f91be2ea03`.
  ملاحظة: ملف الـAPK الموجود على القرص بُني قبل رفع رقم الإصدار إلى
  0.4.0، فهو يحمل versionName ‏0.2.0؛ أي بناء جديد من هذا المصدر
  (محليًا أو عبر GitHub Actions) سيحمل versionName ‏0.4.0 / versionCode ‏3.
  ملف الـAPK نفسه مستثنًى من الـZIP (حسب `.gitignore`) — يُرفَع
  كـRelease منفصل إن أردت، أو يُبنى من جديد عبر Actions.
- `gradle-wrapper.jar` موجود الآن في المشروع؛ سير عمل GitHub Actions
  يستخدم Gradle ‏8.9 مباشرة، ويمكن التبديل إلى `./gradlew` في
  `.github/workflows/android.yml` إن أردت.
- وحدة Unity (`unity/KitchenDesignerUnity/`) مصدر فقط ولم تُبنَ بعد؛
  تطبيق Android يبني بدونها، والربط يتم بعد تصدير `unityLibrary`
  من Unity 6 — انظر `unity/README.md`.
- محتويات الـZIP: كامل المشروع ما عدا `app/build/` و`.gradle/` و`.kotlin/`
  و`local.properties` وملفات السجل و`__pycache__` — أي ما لا يلزم للبناء.

---

# GitHub upload — ready steps (English summary)

**Current state (2026-11-23): the repo exists and access is verified.**
Repo **`kitchen-designer-muse`** on **Abdfaith82** (created 2026-11-22)
currently holds only `README.md` and `test.txt`. GitHub is connected to
Muse and access was verified on 2026-11-22 23:04. The project is **v0.4.0**;
upload `kitchen-designer-android-v0.4.0.zip` (not the old v0.2.0 zip) via
the web UI or the git commands above. After the first push, the included
GitHub Actions workflow builds a debug APK automatically
(artifact `app-debug-apk`). A debug APK was already built locally
(25.6 MB, SHA-256 above); it is excluded from the zip by `.gitignore`.
