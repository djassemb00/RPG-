# RPG- - ألعاب الطاولة (Board Games)

هيكل مشروع Android Native (Kotlin + Jetpack Compose) لتطبيق ألعاب طاولة يدعم وضعين للعرض: **2D** و **3D**.

## ما الذي يتضمنه هذا الهيكل

- شاشة رئيسية تعرض الألعاب المدعومة:
  - الشطرنج
  - الطاولة (Backgammon)
  - لودو
  - الداما
- شاشة لعبة مع مفتاح تبديل بين وضع **2D** و **3D**.
- عرض 2D مرسوم بـ Compose Canvas (لوحة تجريبية لكل لعبة).
- عرض 3D تجريبي باستخدام [SceneView](https://github.com/SceneView/sceneview-android) / Filament بعقد ثلاثية أبعاد إجرائية بسيطة.
- بنية قابلة للتوسع لإضافة منطق اللعب، الذكاء الاصطناعي، اللعب الجماعي، وحفظ الحالة.

## التقنيات

- Kotlin
- Jetpack Compose (Material 3)
- Navigation Compose
- Lifecycle ViewModel
- SceneView (`io.github.sceneview:sceneview`) لوضع 3D

## هيكل المشروع

```text
app/
  src/main/
    java/com/arena/boardgames/
      MainActivity.kt
      BoardGamesApp.kt
      model/
        BoardMode.kt
        GameType.kt
      ui/
        components/
          Board2D.kt
          Board3D.kt
        navigation/
          BoardGamesNavGraph.kt
        screens/
          game/
            GameScreen.kt
            GameViewModel.kt
          home/
            HomeScreen.kt
        theme/
          Color.kt
          Theme.kt
          Type.kt
    res/
```

## التشغيل

1. افتح المشروع في **Android Studio** (إصدار يدعم AGP 8.7+).
2. تأكد من تثبيت Android SDK 35 و JDK 17.
3. دع Gradle يقوم بمزامنة الاعتمادات.
4. شغّل التطبيق على جهاز أو محاكي Android (minSdk 26).

> ملاحظة: هذا المستودع يحتوي على هيكل skeleton/MVP. لوحة اللعب الحالية تجريبية لعرض وضعي 2D/3D، وليست تنفيذًا كاملًا لقواعد كل لعبة.

## خطوات تالية مقترحة

- إضافة محرك قواعد لكل لعبة (حركات صحيحة، فوز/خسارة، أدوار).
- فصل حالة اللعبة إلى `state` models + reducers/use-cases.
- استبدال القطع الإجرائية في 3D بنماذج `.glb` وتحريك الحركة بالكاميرا/اللمس.
- إضافة وضع لاعب ضد لاعب محليًا، ثم لاعب ضد AI.
- إضافة اختبارات وحدة لقواعد اللعب واختبارات UI للتنقل.
