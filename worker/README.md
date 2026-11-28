# djb ai — cloud worker

نفس عقل التطبيق، لكنه يعمل حين يكون الهاتف مغلقًا. ملف واحد بلا أي تبعيات.

## ما يفعله

| المسار | الوظيفة |
|---|---|
| `POST /task` | ينفّذ مهمة نصية ويعيد النتيجة — للعمل الطويل الذي لا يجب أن يستهلك هاتفك |
| `POST /sync` | يخزّن المهارات والدروس والذاكرة، فتعمل عدة أجهزة بعقل واحد |
| `GET /sync?client=…` | يعيد آخر نسخة (للدمج عند العودة) |
| `GET /health` | جاهزية + أي مخزن يعمل فعلًا |

## النشر في 4 خطوات (Vercel)

```bash
# 1) من مجلد worker
npx vercel --prod

# 2) أضف الأسرار (مرة واحدة)
npx vercel env add WORKER_TOKEN production        # كلمة سرّية طويلة تختارها
npx vercel env add OPENAI_API_KEY production      # مفتاح موديلك
npx vercel env add OPENAI_BASE_URL production     # اختياري: أي مزوّد متوافق
npx vercel env add MODEL production               # اختياري: gpt-4o-mini

# 3) للعقل السحابي (اختياري لكنه يجعل التزامن دائمًا)
npx vercel env add SUPABASE_URL production
npx vercel env add SUPABASE_SERVICE_KEY production
```

ثم في تطبيق أندرويد: الإعدادات ← المتقدمة ← **السحابة**، أدخل رابط العامل و`WORKER_TOKEN`، واضغط «فحص».

## جدول Supabase (إن استخدمت العقل السحابي)

```sql
create table djb_sync (
  client_id text primary key,
  snapshot jsonb not null,
  updated_at timestamptz default now()
);
```

## الأمان

- بلا `WORKER_TOKEN` لا ينفّذ العامل أي مهمة — يرفض بدل أن يعمل مفتوحًا.
- العامل **لا يملك shell ولا أدوات جهاز**: التحكم بالهاتف يبقى في الهاتف.
- الأسرار في متغيرات بيئة فقط، ولا تُصدَّر أبدًا إلى التطبيق.
