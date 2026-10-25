package com.chronos.ui

/**
 * Persian, keyed by the English sentence the code draws.
 *
 * `{}` marks where the user's own words go — a goal's name, a weekday, a date —
 * and is carried through untranslated. Numbers are handled by the translator
 * itself and appear as {0}, {1}, … in the key.
 *
 * The register follows the English: plain, explanatory, and willing to say why
 * a setting exists rather than only what it is called.
 */
internal val Fa: Map<String, String> = mapOf(

    // ------------------------------------------------------------ navigation
    "Horizon" to "افق",
    "Day" to "روز",
    "Priorities" to "اولویت‌ها",
    "Setup" to "تنظیمات",
    "Log" to "گزارش",

    // ------------------------------------------------------ shared controls
    "Set" to "ثبت",
    "Cancel" to "انصراف",
    "Choose" to "انتخاب کنید",
    "Not set" to "تعیین نشده",
    "Save" to "ذخیره",
    "Back" to "قبلی",
    "Next" to "بعدی",
    "Previous" to "قبلی",
    "Done" to "انجام شد",
    "Details" to "جزئیات",
    "Hide" to "بستن",
    "Refresh" to "به‌روزرسانی",
    "Name" to "نام",
    "Kind" to "نوع",
    "Type" to "نوع",
    "Unit" to "واحد",
    "From" to "از",
    "Start" to "شروع",
    "End" to "پایان",
    "Deadline" to "مهلت",
    "Ongoing" to "بدون مهلت",
    "Goal" to "هدف",
    "Next: {}" to "بعدی: {}",

    // --------------------------------------------------------------- Setup
    "Nothing is filled in for you, because a default body clock or a default deadline would " +
        "be a guess pretending to be data. The planner stays locked until everything below is answered."
        to "هیچ مقداری از پیش پر نشده است، چون یک ساعت بدنیِ پیش‌فرض یا یک مهلتِ پیش‌فرض حدسی است " +
        "که لباس داده پوشیده. برنامه‌ریز تا وقتی همهٔ موارد زیر پاسخ داده نشوند قفل می‌ماند.",
    "Ready to solve" to "آمادهٔ حل",
    "Everything the model needs is answered." to "هر چه مدل لازم داشت پاسخ داده شده است.",
    " There are still {0} things worth a look." to " هنوز {0} مورد هست که ارزش نگاه کردن دارد.",
    "{0} things still missing" to "{0} مورد هنوز کامل نشده",
    "{0} still missing here" to "{0} مورد در همین بخش کامل نشده",
    "The solver is locked until these are answered. The step bar below shows where."
        to "تا وقتی این‌ها پاسخ داده نشوند حل‌کننده قفل است. نوار مرحله‌های زیر نشان می‌دهد کجا.",
    "Worth a look" to "قابل بررسی",
    "Set priorities" to "تنظیم اولویت‌ها",
    "\\n· and {0} more" to "\\n· و {0} مورد دیگر",

    // wizard steps
    "Body clock" to "ساعت بدن",
    "Scheduler" to "زمان‌بند",
    "Goals" to "اهداف",
    "Activities" to "فعالیت‌ها",
    "Commitments" to "تعهدها",
    "Calendar" to "تقویم",

    "Four numbers define the capacity curve. Rough answers are fine — a week of check-ins " +
        "replaces them with fitted values."
        to "چهار عدد منحنی ظرفیت را می‌سازند. پاسخ تقریبی کافی است — یک هفته ثبت وضعیت، آن‌ها را " +
        "با مقادیر برازش‌شده جایگزین می‌کند.",
    "Safe fatigue line, slack, pessimism and the two penalties. The table at the bottom tells " +
        "you which one to move when a plan feels wrong."
        to "خط ایمن خستگی، حاشیهٔ آزاد، بدبینی و دو جریمه. جدول پایین می‌گوید وقتی برنامه درست " +
        "به نظر نمی‌رسد کدام را جابه‌جا کنید.",
    "At least one, with its unit, hours per unit, ceiling and — if it is finite — a deadline."
        to "دست‌کم یکی، همراه با واحدش، ساعت به ازای هر واحد، سقف روزانه و — اگر پایان‌پذیر است — یک مهلت.",
    "At least one per goal, plus your meals and upkeep. Weekdays, allowed hours, fatigue load " +
        "and recovery."
        to "دست‌کم یکی برای هر هدف، به‌علاوهٔ وعده‌های غذایی و نگهداشت. روزهای هفته، ساعت‌های مجاز، " +
        "بار خستگی و بازیابی.",
    "Classes as fixed sessions, travel as a variable commitment. Anything out of the house " +
        "needs travel defined, or the plan assumes you teleport."
        to "کلاس‌ها به‌صورت جلسه‌های ثابت، رفت‌وآمد به‌صورت تعهد متغیر. هر چیزی که بیرون از خانه " +
        "است به تعریف رفت‌وآمد نیاز دارد، وگرنه برنامه فرض می‌کند شما تله‌پورت می‌شوید.",
    "Optional. Pick a calendar and the next seven days are written into it, and rewritten " +
        "after every rebuild."
        to "اختیاری. یک تقویم انتخاب کنید تا هفت روز آینده در آن نوشته شود و پس از هر بازسازی " +
        "دوباره نوشته شود.",

    // goals
    "Add goal" to "افزودن هدف",
    "New goal" to "هدف جدید",
    "Remove this goal" to "حذف این هدف",
    "Hours per unit" to "ساعت به ازای هر واحد",
    "Your honest estimate on a good day. The planner discounts it downward itself, so do not pad it here."
        to "برآورد صادقانهٔ شما در یک روز خوب. برنامه‌ریز خودش آن را پایین‌تر می‌گیرد، پس این‌جا اغراق نکنید.",
    "Ceiling per day (hours)" to "سقف روزانه (ساعت)",
    "Respected even when a deadline is close." to "حتی وقتی مهلت نزدیک است هم رعایت می‌شود.",
    "Total amount" to "مقدار کل",
    "Already done" to "انجام‌شده",
    "Papers, problem sets, chapters." to "مقاله، تمرین، فصل.",
    "Weekly floor (hours)" to "کف هفتگی (ساعت)",
    "An ongoing goal never finishes; this is what paces it."
        to "هدف بدون مهلت هرگز تمام نمی‌شود؛ این چیزی است که به آن ریتم می‌دهد.",
    "Weight {0}% — set in Priorities, never typed here."
        to "وزن {0}٪ — در بخش اولویت‌ها تعیین می‌شود، هرگز این‌جا تایپ نمی‌شود.",

    // activities
    "An activity is how a goal gets worked on. Upkeep earns nothing but restores capacity, " +
        "which is why lunch pays for itself."
        to "فعالیت، راهی است که روی یک هدف کار می‌شود. نگهداشت چیزی تولید نمی‌کند اما ظرفیت را " +
        "برمی‌گرداند؛ به همین دلیل ناهار هزینهٔ خودش را درمی‌آورد.",
    "Add activity" to "افزودن فعالیت",
    "New activity" to "فعالیت جدید",
    "Remove this activity" to "حذف این فعالیت",
    "Goal work" to "کار روی هدف",
    "Upkeep" to "نگهداشت",
    "Serves" to "مربوط به",
    "Add a goal in step 3 first; goal work has to serve one."
        to "اول در مرحلهٔ ۳ یک هدف اضافه کنید؛ کار روی هدف باید به هدفی وصل باشد.",
    "Days it may happen" to "روزهایی که می‌تواند انجام شود",
    "Shortest useful block (min)" to "کوتاه‌ترین بلوک مفید (دقیقه)",
    "Below this the session is not worth starting." to "کوتاه‌تر از این، شروع جلسه ارزش ندارد.",
    "Longest useful block (min)" to "بلندترین بلوک مفید (دقیقه)",
    "Past this, attention decays faster than output accrues."
        to "بیشتر از این، تمرکز سریع‌تر از خروجی افت می‌کند.",
    "Fatigue load per hour" to "بار خستگی در ساعت",
    "0 costs nothing, 1 is the most draining thing you do."
        to "صفر یعنی بی‌هزینه، یک یعنی فرساینده‌ترین کاری که انجام می‌دهید.",
    "Recovery per hour" to "بازیابی در ساعت",
    "How much it gives back. Meals and walks belong here."
        to "چقدر انرژی برمی‌گرداند. وعده‌های غذایی و پیاده‌روی این‌جا جا می‌گیرند.",
    "Sessions per day" to "تعداد جلسه در روز",
    "Let the model decide" to "مدل تصمیم بگیرد",
    "On auto, the day's hours are split every legal way and the best split wins: one long block " +
        "on a calm day, two shorter ones when a class gets in the way."
        to "در حالت خودکار، ساعت‌های روز به هر شکل مجاز تقسیم می‌شود و بهترین تقسیم برنده است: " +
        "یک بلوک بلند در روز آرام، دو بلوک کوتاه‌تر وقتی کلاس وسط راه است.",
    "Needs a high-focus window" to "به بازهٔ تمرکز بالا نیاز دارد",
    "Counted separately in the capacity audit." to "در ممیزی ظرفیت جداگانه شمرده می‌شود.",
    "Happens out of the house" to "بیرون از خانه انجام می‌شود",
    "Out-of-house items decide when the return trip may start."
        to "موارد بیرون از خانه تعیین می‌کنند سفر برگشت از چه زمانی می‌تواند شروع شود.",
    "Allowed hours" to "ساعت‌های مجاز",
    "Add a window" to "افزودن بازه",
    "Several windows are allowed. A morning and an afternoon slot is common."
        to "چند بازه مجاز است. یک بازهٔ صبح و یک بازهٔ بعدازظهر رایج است.",
    "Pinned to a fixed time on these days" to "میخ‌شده به ساعتی ثابت در این روزها",
    "Pin a day" to "میخ کردن یک روز",
    "A pin overrides the window. Use it for things that are fixed on class days but flexible otherwise."
        to "میخ بر بازه اولویت دارد. برای کارهایی که در روزهای کلاس ثابت‌اند ولی در بقیهٔ روزها " +
        "انعطاف دارند از آن استفاده کنید.",
    "{0}–{1} min · load {2} · recovery {3}" to "{0}–{1} دقیقه · بار {2} · بازیابی {3}",
    " · out of the house" to " · بیرون از خانه",
    " · sessions chosen by the model" to " · تعداد جلسه‌ها را مدل انتخاب می‌کند",

    // commitments
    "Classes and anything else whose time is not yours to choose, plus the travel that wraps around it."
        to "کلاس‌ها و هر چیز دیگری که زمانش دست شما نیست، به‌علاوهٔ رفت‌وآمدی که دور آن می‌پیچد.",
    "Add commitment" to "افزودن تعهد",
    "New commitment" to "تعهد جدید",
    "Remove this commitment" to "حذف این تعهد",
    "Fixed sessions" to "جلسه‌های ثابت",
    "Travel around a session" to "رفت‌وآمد پیرامون یک جلسه",
    "Sessions" to "جلسه‌ها",
    "Add a session" to "افزودن جلسه",
    "Load for this session" to "بار این جلسه",
    "Recovery for this session" to "بازیابی این جلسه",
    "Leave these empty to use the commitment's own load of {} and recovery of {}. Fill them in " +
        "when one session drains more than the others — a four-hour lab against a lecture."
        to "برای استفاده از بار {} و بازیابی {} خودِ تعهد، این‌ها را خالی بگذارید. وقتی پرشان کنید " +
        "که یک جلسه بیشتر از بقیه انرژی می‌برد — مثلاً آزمایشگاه چهارساعته در برابر یک کلاس نظری.",
    "Typical one-way time (min)" to "زمان معمول یک‌طرفه (دقیقه)",
    "The median, not the best case." to "میانه، نه بهترین حالت.",
    "Variability" to "پراکندگی",
    "0.1 is reliable, 0.4 is unpredictable." to "۰.۱ یعنی قابل اتکا، ۰.۴ یعنی غیرقابل پیش‌بینی.",
    "Plan at quantile" to "برنامه‌ریزی روی صدک",
    "0.8 means accepting that you run late one day in five."
        to "۰.۸ یعنی پذیرفتن این‌که یک روز از هر پنج روز دیر می‌رسید.",
    "Getting there" to "رفتن",
    "Coming back" to "برگشتن",
    "Pinned to the session" to "چسبیده به جلسه",
    "Flexible inside a window" to "انعطاف‌پذیر در یک بازه",
    "Does not happen" to "انجام نمی‌شود",
    "The trip wraps around everything marked out of the house that day: the outbound leg must " +
        "finish before the first of them, and the return leg cannot start before the last one " +
        "ends. It is never dropped — if it will not fit, the day plan reports the conflict instead."
        to "سفر دور همهٔ کارهای بیرون از خانهٔ آن روز می‌پیچد: مسیر رفت باید پیش از اولین آن‌ها " +
        "تمام شود و مسیر برگشت نمی‌تواند پیش از پایان آخرینشان شروع شود. هرگز حذف نمی‌شود — اگر " +
        "جا نشود، برنامهٔ روز به‌جای حذف، تعارض را گزارش می‌کند.",

    // body clock
    "Your body clock" to "ساعت بدن شما",
    "Four numbers define the capacity curve. Answer them roughly now — once you have a week of " +
        "check-ins the model fits them from your own ratings and these become a starting point " +
        "rather than a guess you are stuck with."
        to "چهار عدد منحنی ظرفیت را می‌سازند. فعلاً تقریبی پاسخ دهید — وقتی یک هفته ثبت وضعیت " +
        "داشته باشید، مدل آن‌ها را از امتیازهای خودتان برازش می‌کند و این‌ها به نقطهٔ شروع تبدیل " +
        "می‌شوند، نه حدسی که با آن گیر افتاده‌اید.",
    "Sleep starts" to "شروع خواب",
    "Sleep ends" to "پایان خواب",
    "Your sharpest hour" to "تیزترین ساعت شما",
    "When you feel sharpest, ignoring how long you have been awake."
        to "زمانی که بیشترین تیزی ذهنی را دارید، صرف‌نظر از این‌که چند ساعت بیدار بوده‌اید.",
    "Baseline capacity" to "ظرفیت پایه",
    "Your average capability on an ordinary rested day. Raising it lifts the whole curve."
        to "توانایی متوسط شما در یک روزِ عادیِ سرحال. بالا بردنش کل منحنی را بالا می‌برد.",
    "How much time of day matters" to "اهمیت ساعتِ روز",
    "High if mornings and evenings feel very different. Near zero if every hour is alike."
        to "اگر صبح و عصر برایتان خیلی فرق دارند بالا، اگر همهٔ ساعت‌ها شبیه‌اند نزدیک صفر.",
    "How much being awake costs" to "هزینهٔ بیدار بودن",
    "High if you fade late in the day regardless of workload."
        to "اگر بدون توجه به حجم کار، آخر روز افت می‌کنید بالا.",
    "How much work itself drains" to "میزان فرسایندگی خودِ کار",
    "High if two hard sessions back to back leave you useless."
        to "اگر دو جلسهٔ سنگینِ پشت‌سرهم از کار می‌اندازدتان بالا.",
    "The last two are easy to confuse. If you are tired by evening even on a day you did " +
        "nothing, the third is high. If only hard work does it, the fourth is."
        to "دو مورد آخر راحت با هم اشتباه می‌شوند. اگر حتی در روزی که کاری نکرده‌اید عصر خسته‌اید، " +
        "سومی بالاست. اگر فقط کار سنگین این کار را می‌کند، چهارمی.",

    // solver
    "How the scheduler behaves" to "رفتار زمان‌بند",
    "Safe fatigue line" to "خط ایمن خستگی",
    "Below this, fatigue is free. Above it, every extra unit is penalised."
        to "زیر این خط، خستگی رایگان است. بالای آن، هر واحد اضافه جریمه دارد.",
    "Recovery while resting" to "بازیابی هنگام استراحت",
    "How fast doing nothing restores you. Too low and the planner shoves work into the evening."
        to "بی‌کاری چقدر سریع شما را بازمی‌گرداند. خیلی پایین باشد، برنامه‌ریز کارها را به عصر هل می‌دهد.",
    "Slack kept free" to "حاشیهٔ آزاد نگه‌داشته‌شده",
    "Share of open time never scheduled. 0.10 means one hour in ten stays empty on purpose."
        to "سهمی از وقت آزاد که هرگز برنامه‌ریزی نمی‌شود. ۰.۱۰ یعنی یک ساعت از هر ده ساعت عمداً خالی می‌ماند.",
    "Pessimism" to "بدبینی",
    "How far below your estimate the planner assumes you will perform. 0 trusts you fully."
        to "برنامه‌ریز چقدر پایین‌تر از برآورد شما فرض می‌کند عمل می‌کنید. صفر یعنی اعتماد کامل.",
    "Cost of starting a block" to "هزینهٔ شروع یک بلوک",
    "Raise it for fewer, longer sessions." to "برای جلسه‌های کمتر و بلندتر بالا ببرید.",
    "Cost of overworking" to "هزینهٔ کار بیش از حد",
    "How hard the planner avoids pushing past the safe line."
        to "برنامه‌ریز چقدر از عبور از خط ایمن پرهیز می‌کند.",
    "Planning horizon (days)" to "افق برنامه‌ریزی (روز)",
    "If the plans feel wrong, start here" to "اگر برنامه‌ها درست به نظر نمی‌رسند، از این‌جا شروع کنید",
    "Days feel too packed" to "روزها خیلی فشرده‌اند",
    "Raise cost of overworking, or lower the safe fatigue line"
        to "هزینهٔ کار بیش از حد را بالا ببرید، یا خط ایمن خستگی را پایین بیاورید",
    "Days feel too empty" to "روزها خیلی خالی‌اند",
    "Lower pessimism, or lower slack kept free"
        to "بدبینی را کم کنید، یا حاشیهٔ آزاد را کاهش دهید",
    "Too many short scattered sessions" to "جلسه‌های کوتاه و پراکندهٔ زیاد",
    "Raise cost of starting a block, or raise each activity's shortest block"
        to "هزینهٔ شروع بلوک را بالا ببرید، یا کوتاه‌ترین بلوک هر فعالیت را بیشتر کنید",
    "Everything crowds into the morning" to "همه چیز در صبح جمع می‌شود",
    "Lower how much time of day matters, or move your sharpest hour later"
        to "اهمیت ساعتِ روز را کم کنید، یا تیزترین ساعتتان را دیرتر بگذارید",
    "Evenings look unusable" to "عصرها غیرقابل‌استفاده به نظر می‌رسند",
    "Lower how much being awake costs" to "هزینهٔ بیدار بودن را کم کنید",
    "Mornings sit empty while work piles up at night" to "صبح‌ها خالی می‌مانند و کار شب‌ها تلنبار می‌شود",
    "Raise recovery while resting" to "بازیابی هنگام استراحت را بالا ببرید",

    // calendar
    "Google Calendar" to "تقویم گوگل",
    "Seven rolling days are written and rewritten as the plan changes. A month of guessed " +
        "blocks would only clutter a calendar you also live in."
        to "هفت روزِ غلتان نوشته و با تغییر برنامه دوباره نوشته می‌شوند. یک ماه بلوکِ حدسی فقط " +
        "تقویمی را شلوغ می‌کند که خودتان هم در آن زندگی می‌کنید.",
    "Allow calendar access" to "اجازهٔ دسترسی به تقویم",
    "Asked only now, not on first launch. Nothing is read or written until you pick a calendar."
        to "فقط همین حالا پرسیده می‌شود، نه در اولین اجرا. تا وقتی تقویمی انتخاب نکنید چیزی خوانده " +
        "یا نوشته نمی‌شود.",
    "{} — reaches google.com" to "{} — به google.com می‌رسد",
    "{} — stays on this phone" to "{} — روی همین گوشی می‌ماند",
    "Only a Google account calendar reaches google.com; a local one shows on the phone alone. " +
        "The app cannot create a new calendar inside a Google account — only Google's own sync " +
        "adapter may do that — so pick an existing one to write into."
        to "فقط تقویمِ یک حساب گوگل به google.com می‌رسد؛ تقویم محلی تنها روی گوشی دیده می‌شود. " +
        "این اپ نمی‌تواند داخل حساب گوگل تقویم تازه بسازد — این کار فقط از عهدهٔ همگام‌سازِ خود " +
        "گوگل برمی‌آید — پس یکی از تقویم‌های موجود را برای نوشتن انتخاب کنید.",
    "Phone-only calendar" to "تقویم فقط روی گوشی",
    "Include meals, travel and upkeep" to "شامل وعده‌های غذایی، رفت‌وآمد و نگهداشت",
    "Off sends only goal work, which keeps a shared calendar readable."
        to "خاموش باشد فقط کار روی هدف فرستاده می‌شود، که تقویم مشترک را خوانا نگه می‌دارد.",
    "Reminder, minutes before" to "یادآوری، چند دقیقه قبل",
    "The calendar app fires these itself, so the phone needs no alarm permission. Leave it empty " +
        "for no reminder."
        to "خودِ اپ تقویم این‌ها را اعلام می‌کند، پس گوشی به مجوز هشدار نیاز ندارد. برای نداشتن " +
        "یادآوری، خالی بگذاریدش.",
    "Send seven days" to "ارسال هفت روز",
    "Remove mine" to "حذف موارد من",
    "Sending twice never duplicates: this app's own events for those days are replaced, and " +
        "anything you put in that calendar yourself is left untouched."
        to "ارسال دوباره هرگز تکراری نمی‌سازد: رویدادهای خودِ این اپ برای آن روزها جایگزین " +
        "می‌شوند و هر چیزی که خودتان در آن تقویم گذاشته‌اید دست‌نخورده می‌ماند.",

    // ------------------------------------------------------------- Horizon
    "The whole month is allocated before any single day is arranged. Each goal's remaining " +
        "work is spread over the days that can hold it, serving whichever goal has the least " +
        "room to spare first."
        to "کل ماه پیش از چیدمان هر روزی تخصیص داده می‌شود. کار باقی‌ماندهٔ هر هدف روی روزهایی " +
        "پخش می‌شود که جایش را دارند، و هدفی که کمترین فرصت را دارد اول سرویس می‌گیرد.",
    "Planning from this date. Closing out a day re-solves everything that is left."
        to "برنامه‌ریزی از این تاریخ. بستن یک روز، هر چه باقی مانده را دوباره حل می‌کند.",
    "Close out this day" to "بستن این روز",
    "Rebuild" to "بازسازی",
    "No plan yet" to "هنوز برنامه‌ای نیست",
    "Press Rebuild to solve the horizon." to "برای حل افق، بازسازی را بزنید.",
    "This plan no longer matches your settings" to "این برنامه دیگر با تنظیمات شما نمی‌خواند",
    "Something you changed would move the schedule. It has been left alone on purpose — press " +
        "Rebuild when you want the new version."
        to "چیزی که تغییر دادید برنامه را جابه‌جا می‌کند. عمداً دست‌نخورده مانده — هر وقت نسخهٔ " +
        "جدید را خواستید بازسازی را بزنید.",
    "Past their deadline" to "از مهلتشان گذشته",
    ". The planner treats this as due immediately, which distorts everything after it. Either " +
        "move the deadline or write off the remainder."
        to ". برنامه‌ریز این را سررسیدِ فوری می‌گیرد، که همه چیزِ بعد از آن را به‌هم می‌ریزد. " +
        "یا مهلت را جابه‌جا کنید یا باقی‌مانده را کنار بگذارید.",
    "{} ({}h outstanding)" to "{} ({} ساعت باقی‌مانده)",
    "These deadlines cannot all be met" to "این مهلت‌ها همگی قابل رسیدن نیستند",
    "By {}, {} need {}h but only {}h exists before then. You are {}h short — move a deadline, " +
        "cut scope, or find time elsewhere."
        to "تا {}، {} به {} ساعت نیاز دارند ولی تا آن موقع فقط {} ساعت وجود دارد. {} ساعت کم " +
        "می‌آورید — مهلتی را جابه‌جا کنید، دامنه را کم کنید، یا از جای دیگری وقت پیدا کنید.",
    "Fits on paper, but the pacing rules leave work over" to "روی کاغذ جا می‌شود، اما قواعد ریتم کار باقی می‌گذارند",
    ". Usually the daily ceiling or the weekday restriction is what binds."
        to ". معمولاً سقف روزانه یا محدودیت روزهای هفته است که گره می‌اندازد.",
    "Every deadline in the horizon is reachable" to "همهٔ مهلت‌های افق قابل رسیدن‌اند",
    "The slack reserve is untouched." to "ذخیرهٔ حاشیهٔ آزاد دست‌نخورده است.",

    "Deadline check" to "بررسی مهلت‌ها",
    "Every dated goal has somewhere to put all of its hours before its deadline."
        to "هر هدفِ مهلت‌دار، جایی برای همهٔ ساعت‌هایش پیش از مهلتش دارد.",
    "Checked against the days the goal is allowed, the hours its activities may use, and the " +
        "room left once sleep, classes, travel and upkeep are taken out."
        to "در برابر روزهایی که هدف مجاز است، ساعت‌هایی که فعالیت‌هایش می‌توانند استفاده کنند، و " +
        "جایی که پس از کسر خواب، کلاس، رفت‌وآمد و نگهداشت می‌ماند بررسی شده است.",
    "These goals do not finish" to "این اهداف تمام نمی‌شوند",
    "Priority is finishing the goals, not fitting them in loosely — so this is reported rather " +
        "than quietly paced short. The suggestions below are ordered by what they cost you: the " +
        "first ones move the same work, they do not add any."
        to "اولویت، به پایان رساندن اهداف است، نه جا دادن سرسری آن‌ها — پس این موضوع گزارش می‌شود " +
        "نه این‌که بی‌صدا کمتر از نیاز ریتم بگیرد. پیشنهادهای زیر بر اساس هزینه‌ای که برای شما " +
        "دارند مرتب شده‌اند: اولی‌ها همان کار را جابه‌جا می‌کنند، کاری اضافه نمی‌کنند.",
    "Short by" to "کسری",
    "of {0}h needed" to "از {0} ساعتِ لازم",
    "Can take" to "ظرفیت پذیرش",
    "Room per weekday" to "جا در هر روز هفته",
    "Hours inside this goal's allowed windows that are free of sleep, classes and travel, " +
        "counted only in stretches long enough for one session. A dash means the goal can never " +
        "run that day as configured."
        to "ساعت‌هایی داخل بازه‌های مجاز این هدف که از خواب، کلاس و رفت‌وآمد آزادند، و فقط در " +
        "تکه‌هایی شمرده شده‌اند که برای یک جلسه به‌اندازهٔ کافی بلند باشند. خط تیره یعنی هدف با " +
        "این تنظیمات هرگز نمی‌تواند آن روز اجرا شود.",
    "What would fix it" to "چه چیزی آن را حل می‌کند",
    "Same work" to "همان کار",
    "Costs fatigue" to "هزینهٔ خستگی",
    "Changes the goal" to "هدف را تغییر می‌دهد",
    "clears it" to "کاملاً حل می‌کند",
    "Nothing the planner tried recovered the hours on its own. The gap is large enough that it " +
        "needs more than one change — try the deadline and the scope together."
        to "هیچ‌کدام از مواردی که برنامه‌ریز امتحان کرد به‌تنهایی ساعت‌ها را جبران نکرد. شکاف آن‌قدر " +
        "بزرگ است که به بیش از یک تغییر نیاز دارد — مهلت و دامنه را با هم امتحان کنید.",

    "Allocation across the next {0} days" to "تخصیص در {0} روز آینده",
    "Short bars on class-heavy days are not a flaw: that day genuinely has less room."
        to "میله‌های کوتاه در روزهای پرکلاس ایراد نیستند: آن روز واقعاً جای کمتری دارد.",
    "Unallocated" to "تخصیص‌نیافته",
    "Progress" to "پیشرفت",
    "Change what you have finished, or move a deadline, and everything downstream reflows."
        to "آنچه تمام کرده‌اید را تغییر دهید یا مهلتی را جابه‌جا کنید، همه چیزِ پایین‌دست دوباره جاری می‌شود.",
    "Ongoing, paced at {0}h per week" to "بدون مهلت، با ریتم {0} ساعت در هفته",
    "Where the days go" to "روزها کجا می‌روند",
    "free {0}h · upkeep {1}h · travel {2}h · usable {3}h"
        to "آزاد {0} ساعت · نگهداشت {1} ساعت · رفت‌وآمد {2} ساعت · قابل‌استفاده {3} ساعت",

    // ----------------------------------------------------------------- Day
    "The hours for this day come from the horizon plan. What gets decided here is only the " +
        "arrangement: where each block sits so it buys the most value per unit of fatigue."
        to "ساعت‌های این روز از برنامهٔ افق می‌آیند. آنچه این‌جا تصمیم گرفته می‌شود فقط چیدمان " +
        "است: هر بلوک کجا بنشیند تا بیشترین ارزش را به ازای هر واحد خستگی بخرد.",
    "Day {0}" to "روز {0}",
    "Capacity" to "ظرفیت",
    "Fatigue" to "خستگی",
    "Fixed" to "ثابت",
    "Open" to "آزاد",
    "Effective work" to "کار مؤثر",
    "{0}% of {1}h at the desk" to "{0}٪ از {1} ساعتِ پشت میز",
    "Above the safe line" to "بالای خط ایمن",
    "unit-hours of overload" to "واحد-ساعتِ اضافه‌بار",
    "Worth knowing about this day" to "دانستنش دربارهٔ این روز می‌ارزد",
    "{0} item(s) did not fit" to "{0} مورد جا نشد",
    "About this gap" to "دربارهٔ این شکاف",
    "Why this block is here" to "چرا این بلوک این‌جاست",
    "Slot" to "بازه",
    "{}–{} ({} min)" to "{}–{} ({} دقیقه)",
    "Reason" to "دلیل",
    "Mean capacity" to "ظرفیت میانگین",
    "Effective output" to "خروجی مؤثر",
    "{0} of {1} minutes" to "{0} از {1} دقیقه",
    "Effect on capacity" to "اثر بر ظرفیت",
    "load {0}, recovery {1} per hour" to "بار {0}، بازیابی {1} در ساعت",
    "Fixed commitment, not negotiable" to "تعهد ثابت، غیرقابل‌مذاکره",
    "Placement" to "جای‌گذاری",
    "Forced in: travel is not optional" to "به‌اجبار جا داده شد: رفت‌وآمد اختیاری نیست",
    "Timetable" to "جدول زمانی",

    // ---------------------------------------------------------- Priorities
    "Relative priorities" to "اولویت‌های نسبی",
    "Compare goals two at a time instead of assigning numbers. What comes out is what the " +
        "scheduler trades against fatigue."
        to "به‌جای دادن عدد، اهداف را دوبه‌دو مقایسه کنید. آنچه بیرون می‌آید همان چیزی است که " +
        "زمان‌بند در برابر خستگی مبادله می‌کند.",
    "Not enough goals to compare" to "اهداف برای مقایسه کافی نیستند",
    "Add at least two goals in Setup and they will appear here."
        to "دست‌کم دو هدف در تنظیمات اضافه کنید تا این‌جا ظاهر شوند.",
    "Equally valuable per hour" to "به ازای هر ساعت، به یک اندازه ارزشمند",
    "{} is {}× more valuable per hour" to "{} به ازای هر ساعت {} برابر ارزشمندتر است",
    "Derived weights" to "وزن‌های استخراج‌شده",
    "Use these weights" to "اعمال این وزن‌ها",
    "Consistency" to "سازگاری",
    "Internally consistent. Anything below 0.10 is fine." to "از درون سازگار است. هر مقدار زیر ۰.۱۰ خوب است.",
    "Your judgments contradict each other. The least consistent pair is {} against {} — revisit " +
        "only that one."
        to "قضاوت‌های شما با هم در تضادند. ناسازگارترین جفت، {} در برابر {} است — فقط همان یکی را " +
        "دوباره ببینید.",

    // ----------------------------------------------------------------- Log
    "Different things teach the model different lessons. Goal work says how much you got done " +
        "and what it cost; upkeep says how much you got back; a check-in takes ten seconds and " +
        "is what actually pins down your body clock."
        to "چیزهای مختلف درس‌های مختلفی به مدل می‌دهند. کار روی هدف می‌گوید چقدر انجام شد و چه " +
        "هزینه‌ای داشت؛ نگهداشت می‌گوید چقدر پس گرفتید؛ ثبت وضعیت ده ثانیه می‌گیرد و همان چیزی " +
        "است که واقعاً ساعت بدن شما را تثبیت می‌کند.",
    "A session" to "یک جلسه",
    "Energy check-in" to "ثبت وضعیت انرژی",
    "Travel time" to "زمان رفت‌وآمد",
    "What did you do" to "چه کاری انجام دادید",
    "Time" to "زمان",
    "Goal work: output and cost." to "کار روی هدف: خروجی و هزینه.",
    "Upkeep: how much it restored you." to "نگهداشت: چقدر شما را بازگرداند.",
    "Planned minutes" to "دقیقه‌های برنامه‌ریزی‌شده",
    "What the schedule said." to "آنچه برنامه گفته بود.",
    "Actual minutes" to "دقیقه‌های واقعی",
    "What it really took." to "آنچه واقعاً طول کشید.",
    "Target reached (%)" to "درصد رسیدن به هدف",
    "Against the target you committed to at the start of the week, not how it felt afterwards."
        to "در برابر هدفی که ابتدای هفته به آن متعهد شدید، نه حسی که بعداً داشتید.",
    "Focus during the session" to "تمرکز در طول جلسه",
    "1 scattered" to "۱ پراکنده",
    "5 razor sharp" to "۵ کاملاً تیز",
    "How drained afterwards" to "چقدر خسته بعد از آن",
    "1 fresh" to "۱ سرحال",
    "5 spent" to "۵ تمام‌شده",
    "teaches this activity's fatigue load" to "بار خستگی این فعالیت را به مدل می‌آموزد",
    "How restored afterwards" to "چقدر بازیابی‌شده بعد از آن",
    "1 no change" to "۱ بدون تغییر",
    "5 fully reset" to "۵ کاملاً بازنشانی‌شده",
    "teaches this activity's recovery rate" to "نرخ بازیابی این فعالیت را به مدل می‌آموزد",
    "How sharp do you feel right now" to "همین حالا چقدر تیز هستید",
    "1 foggy" to "۱ مه‌آلود",
    "5 absorbed" to "۵ کاملاً درگیر",
    "teaches your capacity curve" to "منحنی ظرفیت شما را به مدل می‌آموزد",
    "Which trip" to "کدام سفر",
    "Door to door (min)" to "در تا در (دقیقه)",
    "One leg. A few of these and the planner stops guessing."
        to "یک مسیر. چندتایی از این‌ها و برنامه‌ریز دیگر حدس نمی‌زند.",
    "Anything unusual about this?" to "چیز غیرعادی‌ای در کار بود؟",
    "The first five mark an outside cause. Ratings carrying one are kept in your history but " +
        "left out of the body-clock fit — they describe the day, not you."
        to "پنج مورد اول علتی بیرونی را نشان می‌دهند. امتیازهایی که یکی از این‌ها را دارند در " +
        "تاریخچه می‌مانند اما از برازش ساعت بدن کنار گذاشته می‌شوند — آن‌ها آن روز را توصیف " +
        "می‌کنند، نه شما را.",
    "Recalibrate" to "کالیبراسیون دوباره",
    "Calibration opens at five entries, but the body clock only moves once six clean energy " +
        "ratings exist. Until then your own estimates stand, held pessimistic so the schedule " +
        "under-promises."
        to "کالیبراسیون از پنج ورودی باز می‌شود، اما ساعت بدن فقط وقتی شش امتیاز انرژیِ پاک وجود " +
        "داشته باشد جابه‌جا می‌شود. تا آن موقع برآوردهای خودتان پابرجاست و بدبینانه نگه داشته " +
        "می‌شود تا برنامه کمتر از توان قول بدهد.",
    "What changed" to "چه چیزی تغییر کرد",
    "Estimates are pulled toward your original numbers and only drift as evidence accumulates, " +
        "so one strange day cannot rewrite the model. Anything fitted is still editable in Setup."
        to "برآوردها به سمت اعداد اولیهٔ شما کشیده می‌شوند و فقط با انباشته شدن شواهد جابه‌جا " +
        "می‌شوند، پس یک روز عجیب نمی‌تواند مدل را بازنویسی کند. هر چیز برازش‌شده همچنان در " +
        "تنظیمات قابل ویرایش است.",
    "History" to "تاریخچه",
    "{0} sessions · {1} check-ins · {2} trips" to "{0} جلسه · {1} ثبت وضعیت · {2} سفر",
    "Session" to "جلسه",
    "Energy {0}/5" to "انرژی {0}/۵",
    "{} — {}% of target, focus {}/5, drained {}/5"
        to "{} — {}٪ از هدف، تمرکز {}/۵، خستگی {}/۵",
    "{} — restored {}/5" to "{} — بازیابی {}/۵",
    "{} — {} min" to "{} — {} دقیقه",

    // -------------------------------------------------------- app messages
    "Saved." to "ذخیره شد.",
    "Something went wrong." to "مشکلی پیش آمد.",
    "Plan rebuilt." to "برنامه بازسازی شد.",
    "Day closed. The remaining horizon was re-solved." to "روز بسته شد. باقی افق دوباره حل شد.",
    "Weights applied. Rebuild to let them move the plan."
        to "وزن‌ها اعمال شدند. برای این‌که برنامه را جابه‌جا کنند، بازسازی بزنید.",
    "Recalibrated on {0} clean observations." to "با {0} مشاهدهٔ پاک کالیبره شد.",
    "Choose a calendar first." to "اول یک تقویم انتخاب کنید.",
    "A phone-only Chronos calendar was created." to "یک تقویم Chronos فقط روی گوشی ساخته شد.",
    " Calendar updated: {0} blocks." to " تقویم به‌روز شد: {0} بلوک.",
    " Calendar not updated: {}." to " تقویم به‌روز نشد: {}.",

    // --------------------------------------------- readiness, from the Java
    "Sleep start time is not set" to "زمان شروع خواب تعیین نشده",
    "Sleep end time is not set" to "زمان پایان خواب تعیین نشده",
    "Your sharpest hour of the day is not set" to "تیزترین ساعت روزتان تعیین نشده",
    "Baseline capacity is not set" to "ظرفیت پایه تعیین نشده",
    "Sensitivity to time of day is not set" to "حساسیت به ساعتِ روز تعیین نشده",
    "Sensitivity to hours awake is not set" to "حساسیت به ساعت‌های بیداری تعیین نشده",
    "Sensitivity to accumulated work is not set" to "حساسیت به کارِ انباشته تعیین نشده",
    "Safe fatigue line is not set" to "خط ایمن خستگی تعیین نشده",
    "Recovery while resting is not set" to "بازیابی هنگام استراحت تعیین نشده",
    "Slack reserve is not set" to "ذخیرهٔ حاشیهٔ آزاد تعیین نشده",
    "Pessimism is not set" to "بدبینی تعیین نشده",
    "Cost of starting a block is not set" to "هزینهٔ شروع یک بلوک تعیین نشده",
    "Cost of overworking is not set" to "هزینهٔ کار بیش از حد تعیین نشده",
    "Planning horizon is not set" to "افق برنامه‌ریزی تعیین نشده",
    "Sleep start and end are the same time" to "شروع و پایان خواب یک زمان‌اند",
    "Under four hours of sleep is scheduled; the capacity model will not be realistic"
        to "کمتر از چهار ساعت خواب برنامه‌ریزی شده؛ مدل ظرفیت واقع‌بینانه نخواهد بود",
    "No goals defined yet" to "هنوز هدفی تعریف نشده",
    "A goal has no name" to "یک هدف نام ندارد",
    "No activities defined yet" to "هنوز فعالیتی تعریف نشده",
    "An activity has no name" to "یک فعالیت نام ندارد",
    "A commitment has no kind" to "یک تعهد نوع ندارد",
    "Relative priorities have not been set; compare your goals two at a time"
        to "اولویت‌های نسبی تعیین نشده‌اند؛ اهدافتان را دوبه‌دو مقایسه کنید",
    "{}: type is not set" to "{}: نوع تعیین نشده",
    "{}: kind is not set" to "{}: نوع تعیین نشده",
    "{}: hours per unit is not set" to "{}: ساعت به ازای هر واحد تعیین نشده",
    "{}: daily ceiling is not set" to "{}: سقف روزانه تعیین نشده",
    "{}: total amount is not set" to "{}: مقدار کل تعیین نشده",
    "{}: unit is not named" to "{}: واحد نام‌گذاری نشده",
    "{}: deadline is not set" to "{}: مهلت تعیین نشده",
    "{}: weekly floor is not set" to "{}: کف هفتگی تعیین نشده",
    "{}: shortest block is not set" to "{}: کوتاه‌ترین بلوک تعیین نشده",
    "{}: longest block is not set" to "{}: بلندترین بلوک تعیین نشده",
    "{}: fatigue load is not set" to "{}: بار خستگی تعیین نشده",
    "{}: recovery is not set" to "{}: بازیابی تعیین نشده",
    "{}: no weekday is selected" to "{}: هیچ روزی از هفته انتخاب نشده",
    "{}: no allowed hours are set" to "{}: هیچ ساعت مجازی تعیین نشده",
    "{}: no sessions are defined" to "{}: هیچ جلسه‌ای تعریف نشده",
    "{}: not attached to a goal" to "{}: به هیچ هدفی وصل نیست",
    "{}: typical travel time is not set" to "{}: زمان معمول رفت‌وآمد تعیین نشده",
    "{}: travel variability is not set" to "{}: پراکندگی رفت‌وآمد تعیین نشده",
    "{}: planning quantile is not set" to "{}: صدک برنامه‌ریزی تعیین نشده",
    "{}: deadline has passed with work outstanding" to "{}: مهلت گذشته و هنوز کار مانده",
    "{}: longest block is shorter than the shortest" to "{}: بلندترین بلوک از کوتاه‌ترین کوتاه‌تر است",
    "{} has no activity that works on it" to "{} هیچ فعالیتی ندارد که روی آن کار کند",
    "{}: its widest allowed window is {} minutes, shorter than its own shortest block"
        to "{}: عریض‌ترین بازهٔ مجازش {} دقیقه است، کوتاه‌تر از کوتاه‌ترین بلوک خودش",
    "{}: pinned on {}, a day it is not allowed on" to "{}: به {} میخ شده، روزی که در آن مجاز نیست",
    "{}: pinned to {}, which is while you are asleep" to "{}: به {} میخ شده، که وقتی است که خواب‌اید",
    "Something happens away from home but no travel is defined, so the plan would assume you teleport"
        to "کاری بیرون از خانه انجام می‌شود ولی رفت‌وآمدی تعریف نشده، پس برنامه فرض می‌کند تله‌پورت می‌شوید",
    "{} does not finish by {}: {}h of the {}h it needs have nowhere to go. The Horizon tab lists " +
        "what to change."
        to "{} تا {} تمام نمی‌شود: {} ساعت از {} ساعتی که لازم دارد جایی برای رفتن ندارد. تب افق " +
        "فهرست می‌کند چه چیزی را باید تغییر داد.",
    "{}: {}h could not be paced within the daily ceiling and allowed weekdays"
        to "{}: {} ساعت را نشد در سقف روزانه و روزهای مجاز ریتم داد",

    // ------------------------------------------ feasibility, from the Java
    "The hours this goal is allowed, on the weekdays it is allowed, are already taken by sleep " +
        "or a class — or what is left comes in stretches shorter than one session of it."
        to "ساعت‌هایی که این هدف مجاز است، در روزهایی که مجاز است، از قبل با خواب یا کلاس پر " +
        "شده‌اند — یا آنچه مانده در تکه‌هایی کوتاه‌تر از یک جلسهٔ آن است.",
    "The days before the deadline have the room, but the ceiling of {0}h a day will not let the " +
        "goal use it."
        to "روزهای پیش از مهلت جا دارند، اما سقف {0} ساعت در روز نمی‌گذارد هدف از آن استفاده کند.",
    "There are not enough free hours before the deadline, once sleep, classes, travel, upkeep " +
        "and the slack reserve are taken out."
        to "پس از کسر خواب، کلاس، رفت‌وآمد، نگهداشت و ذخیرهٔ حاشیهٔ آزاد، ساعت آزادِ کافی پیش از " +
        "مهلت وجود ندارد.",
    "Widen the allowed hours of \"{}\" — its windows are what is turning the day away"
        to "ساعت‌های مجاز «{}» را بازتر کنید — بازه‌های آن است که روز را پس می‌زند",
    "Allow \"{}\" on {} as well" to "«{}» را در {} هم مجاز کنید",
    "Shorten the shortest useful block of \"{}\" from {} to {} min"
        to "کوتاه‌ترین بلوک مفید «{}» را از {} به {} دقیقه کم کنید",
    "Raise the daily ceiling for this goal from {0}h to {1}h"
        to "سقف روزانهٔ این هدف را از {0} به {1} ساعت ببرید",
    "Lower the slack kept free from {0} to {1} — this is the reserve that absorbs a day running long"
        to "حاشیهٔ آزاد را از {0} به {1} کم کنید — این همان ذخیره‌ای است که طولانی شدن یک روز را جذب می‌کند",
    "Lower pessimism from {0} to {1} — the planner then reserves fewer hours per unit, which " +
        "only helps if your estimate was already honest"
        to "بدبینی را از {0} به {1} کم کنید — آن‌گاه برنامه‌ریز ساعت کمتری به ازای هر واحد کنار " +
        "می‌گذارد، که فقط اگر برآوردتان از اول صادقانه بوده کمک می‌کند",
    "Shorten your upkeep sessions by a quarter — upkeep is subtracted from every day before " +
        "goals are served"
        to "جلسه‌های نگهداشت را یک‌چهارم کوتاه کنید — نگهداشت پیش از سرویس دادن به اهداف از هر روز کسر می‌شود",
    "Move the deadline {} days later, to {}" to "مهلت را {} روز عقب‌تر ببرید، به {}",
    "Cut the target from {} to {} {}" to "هدف را از {} به {} {} کاهش دهید",

    // ---------------------------- the day sequencer's own words, from the Java
    "{0}h" to "{0} ساعت",
    "Transition" to "فاصله",
    "Open buffer" to "بافر آزاد",
    "{} had to go outside its usual window." to "«{}» ناچار شد بیرون از بازهٔ معمولش برود.",
    "{}h of {} did not fit today; those hours roll into the horizon."
        to "{} ساعت از «{}» امروز جا نشد؛ آن ساعت‌ها به افق منتقل می‌شوند.",
    "Only {0} minutes, shorter than the smallest block you allow ({1} min), so nothing can go here."
        to "فقط {0} دقیقه، کوتاه‌تر از کوچک‌ترین بلوکی که مجاز کرده‌اید ({1} دقیقه)، پس چیزی این‌جا جا نمی‌شود.",
    "Held back deliberately: this is the slack reserve that absorbs overruns."
        to "عمداً نگه داشته شده: این همان ذخیرهٔ حاشیهٔ آزاد است که طولانی شدن کارها را جذب می‌کند.",
    "Nothing left to schedule. Resting here also lowers fatigue for later blocks."
        to "چیزی برای برنامه‌ریزی نمانده. استراحت این‌جا خستگی بلوک‌های بعدی را هم کم می‌کند.",
    "the day's slack budget was already spent" to "بودجهٔ حاشیهٔ آزاد آن روز از قبل خرج شده بود",
    "no free window inside the allowed hours was long enough"
        to "هیچ بازهٔ آزادی داخل ساعت‌های مجاز به‌اندازهٔ کافی بلند نبود",
    "To be there by {} you would have to leave at {}, which is earlier than you meant to be up."
        to "برای رسیدن تا {} باید ساعت {} راه بیفتید، که زودتر از زمانی است که می‌خواستید بیدار شوید.",
    "You are out until {}, so you do not get home until {}. Shorten the last thing you do away " +
        "from home, or accept the late finish."
        to "تا {} بیرون هستید، پس تا {} به خانه نمی‌رسید. آخرین کارِ بیرون از خانه را کوتاه کنید، " +
        "یا پایان دیر را بپذیرید.",

    // A goal's progress line: the unit is the user's own word, so it stays.
    "{} of {} {}" to "{} از {} {}",

    // ---------------------------- the log's tags and the calibration report
    "Poor sleep" to "خواب بد",
    "Unwell" to "کسالت",
    "Stressed" to "استرس",
    "Skipped a meal" to "نخوردن یک وعده",
    "Noisy or interrupted" to "شلوغ یا با وقفه",
    "Caffeine" to "کافئین",
    "After exercise" to "بعد از ورزش",
    "Late start" to "شروع دیرهنگام",
    "three a day, spread out, is plenty" to "سه بار در روز، پخش‌شده، کافی است",
    "Travel" to "رفت‌وآمد",
    "fitted on {0} rated moments; your sharpest hour moved to {1}:{2}"
        to "روی {0} لحظهٔ امتیازدهی‌شده برازش شد؛ تیزترین ساعت شما به {1}:{2} منتقل شد",
    "fitted on {0} rated moments; your sharpest hour moved to {1}:{2}, ignoring {3} affected by " +
        "an outside cause"
        to "روی {0} لحظهٔ امتیازدهی‌شده برازش شد؛ تیزترین ساعت شما به {1}:{2} منتقل شد، با نادیده " +
        "گرفتن {3} مورد که علتی بیرونی داشتند",
    // Every number is lifted out, including the literal 6, so the key carries both.
    "needs at least {0} clean energy ratings, has {1}. Still using your own estimates."
        to "دست‌کم به {0} امتیاز انرژیِ پاک نیاز دارد، {1} تا دارد. فعلاً از برآوردهای خودتان استفاده می‌شود.",
    "quality {0} to {1}, fatigue load {2} to {3} ({4} sessions)"
        to "کیفیت از {0} به {1}، بار خستگی از {2} به {3} ({4} جلسه)",
    "recovery {0} to {1} ({2} sessions)" to "بازیابی از {0} به {1} ({2} جلسه)",
    "typical {0} to {1} min, variability {2} to {3} ({4} trips)"
        to "زمان معمول از {0} به {1} دقیقه، پراکندگی از {2} به {3} ({4} سفر)",

    // ------------------------------------------------- calendar sync reports
    "{0} blocks across {1} days sent to your calendar."
        to "{0} بلوک در {1} روز به تقویم شما فرستاده شد.",
    "{0} blocks across {1} days sent to your calendar, replacing {2} older ones."
        to "{0} بلوک در {1} روز به تقویم شما فرستاده شد و جای {2} مورد قدیمی را گرفت.",
    "{0} Chronos events removed. Nothing else in that calendar was touched."
        to "{0} رویداد Chronos حذف شد. به چیز دیگری در آن تقویم دست زده نشد.",

    // ------------------------------------------------ changeover and rest
    "Changeover before it (min)" to "زمان تغییر وضعیت پیش از آن (دقیقه)",
    "Time to get into it: changing for the gym, walking to the kitchen. Leave empty for " +
        "anything that happens where you already are — reading and language practice are at " +
        "the same desk."
        to "زمان لازم برای وارد شدن به آن: لباس عوض کردن برای ورزش، رفتن به آشپزخانه. برای هر " +
        "کاری که همان‌جایی که هستید انجام می‌شود خالی بگذارید — مطالعه و تمرین زبان پشت همان میزند.",
    " · {0} min changeover" to " · {0} دقیقه تغییر وضعیت",
    "Changeover" to "تغییر وضعیت",
    "Held free to get into {}, which needs {} minutes to change into."
        to "برای وارد شدن به «{}» آزاد نگه داشته شده، که {} دقیقه زمانِ تغییر وضعیت لازم دارد.",
    "no free window was long enough for it plus the {0} minutes of changeover before it"
        to "هیچ بازهٔ آزادی به‌اندازهٔ کافی بلند نبود که هم خودش و هم {0} دقیقه تغییر وضعیتِ پیش از آن جا شود",

    // ---------------------------------------- what the arranged days cover
    "Checked against the arranged days, not only against the hours that exist."
        to "در برابر روزهای چیده‌شده بررسی شده، نه فقط در برابر ساعت‌هایی که وجود دارند.",
    "These goals do not finish in the arranged days" to "این اهداف در روزهای چیده‌شده تمام نمی‌شوند",
    "Measured against the timetable, not only against the hours that exist. The horizon can " +
        "pace work onto a day whose allowed windows have no room left to arrange it."
        to "در برابر جدول زمانی اندازه‌گیری شده، نه فقط در برابر ساعت‌هایی که وجود دارند. افق " +
        "می‌تواند کار را روی روزی بگذارد که بازه‌های مجازش دیگر جایی برای چیدن آن ندارند.",
    "Actually placed" to "واقعاً چیده‌شده",
    "{0}h of what the horizon allocated could not be arranged in the day itself — the allowed " +
        "hours had no stretch long enough left in them."
        to "{0} ساعت از آنچه افق تخصیص داده بود در خودِ روز قابل چیدن نبود — در ساعت‌های مجاز " +
        "تکه‌ای به‌اندازهٔ کافی بلند باقی نمانده بود.",
    "· {} lost {}h" to "· {} مقدار {} ساعت را از دست داد",
    "The days before the deadline were never given the hours: once sleep, classes, travel, " +
        "upkeep and the slack reserve are taken out, {0}h is all there was to give."
        to "روزهای پیش از مهلت اصلاً آن ساعت‌ها را نگرفتند: پس از کسر خواب، کلاس، رفت‌وآمد، " +
        "نگهداشت و ذخیرهٔ حاشیهٔ آزاد، تنها {0} ساعت برای دادن وجود داشت.",
    "The suggestions below are measured against the capacity model, so treat their hours as a " +
        "direction rather than a promise — press Rebuild to see what the arranged days make of " +
        "a change."
        to "پیشنهادهای زیر در برابر مدل ظرفیت اندازه‌گیری شده‌اند، پس ساعت‌هایشان را یک جهت " +
        "بدانید نه یک قول — برای دیدن این‌که روزهای چیده‌شده با یک تغییر چه می‌کنند، بازسازی را بزنید.",
    "Planning horizon must be between 7 and 90 days" to "افق برنامه‌ریزی باید بین ۷ تا ۹۰ روز باشد",
    "Between 7 and 90 days. Longer costs nothing to solve; it only means the plan reaches " +
        "further than your estimates can be trusted."
        to "بین ۷ تا ۹۰ روز. بلندتر بودنش هزینه‌ای برای حل ندارد؛ فقط یعنی برنامه دورتر از جایی " +
        "می‌رسد که بتوان به برآوردهایتان اعتماد کرد.",

    // ------------------------------------------------------------ language
    "Language" to "زبان",
    "The interface only. Your own words — goal names, activity names — are never translated."
        to "فقط رابط کاربری. واژه‌های خودتان — نام اهداف و فعالیت‌ها — هرگز ترجمه نمی‌شوند.",

    // ------------------------------------------------ building from a date
    "Build the plan" to "ساختن برنامه",
    "Build" to "بساز",
    "Start the plan from" to "برنامه از چه تاریخی شروع شود",
    "First day of the plan" to "نخستین روز برنامه",
    "That day has already passed. The plan will start today."
        to "آن روز گذشته است. برنامه از امروز شروع می‌شود.",
    "Nothing is scheduled before this date, and days before it are not closed out."
        to "پیش از این تاریخ چیزی چیده نمی‌شود و روزهای پیش از آن هم بسته نمی‌شوند.",
    "The plan starts today." to "برنامه از امروز شروع می‌شود.",
    "Press Build the plan to solve the horizon." to "برای حل افق، «ساختن برنامه» را بزنید.",
    "Planning from this date. Each day closes itself at {0}:{1}: what you logged updates the " +
        "model, and the plan is rebuilt from the next day and sent to your calendar."
        to "برنامه‌ریزی از این تاریخ. هر روز ساعت {0}:{1} خودش بسته می‌شود: آنچه ثبت کرده‌اید " +
        "مدل را به‌روز می‌کند، و برنامه از روز بعد دوباره ساخته و به تقویم شما فرستاده می‌شود.",
    "Something you changed would move the schedule. It has been left alone on purpose — press " +
        "Build the plan when you want the new version, or leave it to tonight's rebuild."
        to "چیزی که تغییر دادید برنامه را جابه‌جا می‌کند. عمداً دست‌نخورده مانده — هر وقت نسخهٔ " +
        "جدید را خواستید «ساختن برنامه» را بزنید، یا بگذارید بازسازی امشب انجامش دهد.",
    "These are measured against the capacity model, so treat their hours as a direction rather " +
        "than a promise — press Build the plan to see what the arranged days make of a change."
        to "این‌ها در برابر مدل ظرفیت اندازه‌گیری شده‌اند، پس ساعت‌هایشان را یک جهت بدانید نه یک " +
        "قول — برای دیدن این‌که روزهای چیده‌شده با یک تغییر چه می‌کنند، «ساختن برنامه» را بزنید.",

    // ------------------------------------------------ the day closing itself
    "The day was closed and the plan rebuilt from what you logged."
        to "روز بسته شد و برنامه بر اساس آنچه ثبت کردید دوباره ساخته شد.",
    "Plan rebuilt from the next day." to "برنامه از روز بعد دوباره ساخته شد.",
    "Plan not rebuilt: setup is incomplete." to "برنامه دوباره ساخته نشد: تنظیمات کامل نیست.",
    "Plan rebuilt from the next day. Calendar updated: {0} blocks."
        to "برنامه از روز بعد دوباره ساخته شد. تقویم به‌روز شد: {0} بلوک.",
    "Plan rebuilt from the next day. Calendar not updated: permission is off."
        to "برنامه از روز بعد دوباره ساخته شد. تقویم به‌روز نشد: مجوز تقویم خاموش است.",
    "Plan rebuilt from the next day. Calendar not updated: {}."
        to "برنامه از روز بعد دوباره ساخته شد. تقویم به‌روز نشد: {}.",
    "progress {0} to {1}" to "پیشرفت از {0} به {1}",
    "progress {} to {} {}" to "پیشرفت از {} به {} {}",
    "What changed last night" to "دیشب چه چیزی تغییر کرد",
    "Closed: {}" to "بسته شد: {}",
    "Nothing was logged, so nothing moved." to "چیزی ثبت نشده بود، پس چیزی هم جابه‌جا نشد.",

    // ------------------------------------------------------ the Log screen
    "Tell the model how today really went: your energy hour by hour, and what happened to each " +
        "block of the plan. Nothing needs applying — when the day closes, the model learns from " +
        "this, and tomorrow's plan is rebuilt and sent to your calendar."
        to "به مدل بگویید امروز واقعاً چطور گذشت: انرژی‌تان ساعت به ساعت، و این‌که با هر بلوک " +
        "برنامه چه شد. لازم نیست چیزی را دستی اعمال کنید — وقتی روز بسته شود، مدل از این‌ها " +
        "یاد می‌گیرد و برنامهٔ فردا دوباره ساخته و به تقویم شما فرستاده می‌شود.",
    "This is the day you are reporting on. It closes by itself at the time below."
        to "این روزی است که گزارشش را می‌دهید. در ساعت زیر خودش بسته می‌شود.",
    "The day closes at" to "روز در این ساعت بسته می‌شود",
    "Pick a time after you are usually asleep. Until then you can keep changing today's answers; " +
        "after it, today is read once and the plan is rebuilt from the next day."
        to "ساعتی را انتخاب کنید که معمولاً آن موقع خوابید. تا آن موقع می‌توانید پاسخ‌های امروز را " +
        "تغییر دهید؛ بعد از آن، امروز یک بار خوانده می‌شود و برنامه از روز بعد دوباره ساخته می‌شود.",
    "Energy through the day" to "انرژی در طول روز",
    "Set your sleep hours in Setup first: the list is built from the hours you are awake."
        to "اول ساعت‌های خوابتان را در تنظیمات وارد کنید: این فهرست از ساعت‌های بیداری شما ساخته می‌شود.",
    "Tap how sharp you were in each hour, from foggy to razor sharp. Skip the hours you cannot " +
        "remember — a few honest answers beat a full row of guesses."
        to "برای هر ساعت بزنید که چقدر سرحال و تیز بودید، از مه‌آلود تا کاملاً تیز. ساعت‌هایی را که " +
        "یادتان نیست رها کنید — چند پاسخ صادقانه از یک ردیف کامل حدس بهتر است.",
    "All day" to "کل روز",
    "Outside causes that coloured the whole day. Its ratings are kept, but left out of the " +
        "body-clock fit — they describe that day, not your natural rhythm."
        to "علت‌های بیرونی که روی کل روز اثر گذاشتند. امتیازهای آن روز نگه داشته می‌شوند، اما از " +
        "برازش ساعت بدن کنار می‌روند — آن‌ها آن روز را توصیف می‌کنند، نه ریتم طبیعی شما را.",
    "Nothing planned" to "برنامه‌ای نبود",
    "Why?" to "چرا؟",
    "Outside causes — rating kept out of the fit"
        to "علت‌های بیرونی — امتیاز از برازش کنار گذاشته می‌شود",
    "Inside the body — the curve already accounts for these"
        to "از درون بدن — منحنی خودش این‌ها را حساب می‌کند",
    "Sleepy" to "خواب‌آلود",
    "Mentally tired" to "خستگی ذهنی",
    "After a meal" to "بعد از غذا",
    "{0} of {1} hours rated. These ratings are what fit your body clock: your baseline, the " +
        "height and timing of your daily peak, and how much sleep pressure and accumulated work " +
        "fatigue pull you down. The work fatigue comes from the blocks you report below, so the " +
        "two lists teach the model together."
        to "{0} از {1} ساعت امتیاز گرفت. همین امتیازها ساعت بدن شما را برازش می‌کنند: پایهٔ " +
        "انرژی، ارتفاع و زمان اوج روزانه، و این‌که فشار خواب و خستگیِ انباشتهٔ کار چقدر پایینتان " +
        "می‌کشد. خستگی کار از بلوک‌هایی می‌آید که پایین گزارش می‌دهید، پس این دو فهرست با هم " +
        "به مدل یاد می‌دهند.",
    "What the day held" to "امروز چه چیزهایی داشت",
    "There is no plan yet. Build one on the Horizon tab and its blocks appear here."
        to "هنوز برنامه‌ای نیست. در زبانهٔ افق یکی بسازید تا بلوک‌هایش این‌جا بیایند.",
    "One box per block of today's plan. Open it, say how much of it you did, when you started " +
        "and how it went, and save. Blocks you leave unreported teach the model nothing, which is " +
        "better than a guess."
        to "برای هر بلوک برنامهٔ امروز یک باکس هست. بازش کنید، بگویید چقدرش را انجام دادید، کی " +
        "شروع کردید و چطور پیش رفت، و ذخیره کنید. بلوکی که گزارش نشود چیزی به مدل یاد نمی‌دهد، " +
        "که از حدس زدن بهتر است.",
    "Nothing was planned for this day." to "برای این روز چیزی برنامه‌ریزی نشده بود.",
    "Not reported yet" to "هنوز گزارش نشده",
    "Skipped" to "انجام نشد",
    "Done {0} min · {1}% of target · focus {2}/{3} · drained {4}/{5}"
        to "انجام شد {0} دقیقه · {1}٪ از هدف · تمرکز {2}/{3} · خستگی {4}/{5}",
    "Done {0} min · restored {1}/{2}" to "انجام شد {0} دقیقه · بازیابی {1}/{2}",
    "Took {0} min" to "{0} دقیقه طول کشید",
    "I did it" to "انجامش دادم",
    "I skipped it" to "انجامش ندادم",
    "Started at" to "ساعت شروع",
    "Minutes spent" to "دقیقه‌های صرف‌شده",
    "Planned: {0} min from {1}:{2}. Count only the time you were actually on it."
        to "برنامه: {0} دقیقه از ساعت {1}:{2}. فقط زمانی را بشمارید که واقعاً مشغولش بودید.",
    "How much of what this block was meant to produce got done."
        to "چه مقدار از آنچه این بلوک قرار بود تولید کند انجام شد.",
    "Finished ({})" to "تمام‌شده ({})",
    "Finished (units)" to "تمام‌شده (واحد)",
    "The plan expected {0}. Added to the goal's progress when the day closes."
        to "برنامه {0} انتظار داشت. وقتی روز بسته شود به پیشرفت هدف اضافه می‌شود.",
    "Did anything get in the way?" to "چیزی مانع شد؟",
    "Interruptions, illness and stress keep this session's fatigue out of the fit; its output " +
        "still counts."
        to "وقفه، کسالت و استرس، خستگی این جلسه را از برازش بیرون نگه می‌دارند؛ خروجی‌اش همچنان " +
        "حساب می‌شود.",
    "A skipped block is not a bad block: it does not lower this activity's quality. The work it " +
        "was meant to do stays in the goal and is planned again."
        to "بلوکِ انجام‌نشده بلوک بدی نیست: کیفیت این فعالیت را پایین نمی‌آورد. کاری که قرار بود " +
        "انجام دهد در هدف می‌ماند و دوباره برنامه‌ریزی می‌شود.",
    "Planned {0} min. A few of these and the planner stops guessing."
        to "برنامه: {0} دقیقه. چندتایی از این‌ها و برنامه‌ریز دیگر حدس نمی‌زند.",
    "Clear" to "پاک کردن",
    "{} — skipped" to "{} — انجام نشد",
    "Energy {0}/{1}" to "انرژی {0}/{1}",

    // ------------------------------------------- what may give way for deadlines
    "When a deadline is at risk" to "وقتی یک مهلت در خطر است",
    "Never cut it" to "هرگز کم نشود",
    "Cut it as a last resort" to "در آخرین مرحله کم شود",
    "Cut it first" to "اول از همه کم شود",
    "Only on days before a deadline that would otherwise be missed, and only as many days as it takes."
        to "فقط در روزهای پیش از مهلتی که در غیر این صورت از دست می‌رفت، و فقط به تعداد روزی که لازم است.",
    "This ongoing goal gives up its time on days before a deadline that would otherwise be missed, " +
        "and only as many days as it takes."
        to "این هدف بدون مهلت در روزهای پیش از مهلتی که در غیر این صورت از دست می‌رفت وقتش را واگذار " +
        "می‌کند، و فقط به تعداد روزی که لازم است.",
    "Keep at least (min) when cut" to "دست‌کم این مقدار بماند (دقیقه)",
    "Empty or {0} drops it on that day. A workout that can shrink to a short run says {1}."
        to "خالی یا {0} یعنی آن روز کلاً حذف شود. ورزشی که می‌تواند به یک دویدن کوتاه تبدیل شود، {1} می‌نویسد.",
    "Each session has its own load and recovery: a four-hour lab drains more than a lecture. " +
        "Recovery may stay empty for none."
        to "هر جلسه بار خستگی و بازیابی خودش را دارد: یک آزمایشگاه چهارساعته بیشتر از یک کلاس " +
        "درس خسته می‌کند. بازیابی را اگر ندارد خالی بگذارید.",
    "{}: a session's fatigue load is not set" to "{}: بار خستگی یکی از جلسه‌ها تعیین نشده",
    "{} is left out today to make room for a deadline."
        to "{} امروز حذف شد تا برای یک مهلت جا باز شود.",
    "{} is shortened to {} min today to make room for a deadline."
        to "{} امروز به {} دقیقه کوتاه شد تا برای یک مهلت جا باز شود.",
    "Every deadline is reachable, by giving up some time"
        to "همهٔ مهلت‌ها قابل رسیدن‌اند، با واگذار کردن کمی از وقت‌ها",
    "The activities you allowed to be cut make the room. The list below says what, and when."
        to "فعالیت‌هایی که اجازهٔ کم شدنشان را دادید جا را باز می‌کنند. فهرست زیر می‌گوید چه چیزی و کی.",
    "Given up for the deadlines" to "واگذارشده برای مهلت‌ها",
    "A dated goal did not fit, so the plan took time from activities you marked as allowed to be " +
        "cut — only as much as the deadlines needed, latest days first."
        to "یک هدف مهلت‌دار جا نمی‌شد، پس برنامه از فعالیت‌هایی که اجازهٔ کم شدنشان را داده بودید وقت " +
        "گرفت — فقط به اندازه‌ای که مهلت‌ها لازم داشتند، و از دیرترین روزها شروع کرد.",
    "left out" to "حذف شد",
    "{0} → {1} min" to "{0} ← {1} دقیقه",

    // ----------------------------------------------------- past days in the Log
    "Tell the model how the day really went: your energy hour by hour, and what happened to each " +
        "block of the plan. Nothing needs applying for today — when the day closes, the model learns " +
        "from this, and tomorrow's plan is rebuilt and sent to your calendar."
        to "به مدل بگویید روز واقعاً چطور گذشت: انرژی‌تان ساعت به ساعت، و این‌که با هر بلوک برنامه " +
        "چه شد. برای امروز لازم نیست چیزی را دستی اعمال کنید — وقتی روز بسته شود، مدل از این‌ها یاد " +
        "می‌گیرد و برنامهٔ فردا دوباره ساخته و به تقویم شما فرستاده می‌شود.",
    "Today — the day you are reporting on." to "امروز — روزی که گزارشش را می‌دهید.",
    "A day that has already closed. What you add or change here waits for the Apply button below, " +
        "or for tonight's close."
        to "روزی که بسته شده است. آنچه این‌جا اضافه یا عوض می‌کنید منتظر دکمهٔ «اعمال» می‌ماند، یا " +
        "بستن روز در امشب.",
    "Previous day" to "روز قبل",
    "Next day" to "روز بعد",
    "Today" to "امروز",
    "Entries for past days" to "ورودی‌های روزهای گذشته",
    "{0} entries for days that have already closed are not in the model yet."
        to "{0} ورودی برای روزهایی که بسته شده‌اند هنوز وارد مدل نشده‌اند.",
    "Apply takes them in now: finished units go into your goals' progress and the ratings into the " +
        "model. The plan itself is not rebuilt; if the model moved, the Horizon tab says what changed " +
        "and offers the rebuild. Left alone, they are taken in tonight."
        to "«اعمال» همین حالا واردشان می‌کند: واحدهای تمام‌شده به پیشرفت اهداف و امتیازها به مدل " +
        "می‌روند. خود برنامه دوباره ساخته نمی‌شود؛ اگر مدل تغییر کند، زبانهٔ افق می‌گوید چه چیزی " +
        "عوض شد و ساختن دوباره را پیشنهاد می‌دهد. اگر کاری نکنید، امشب وارد می‌شوند.",
    "Apply" to "اعمال",
    "The plan for this day was not kept — it is from before plans were stored. Use the list below " +
        "to add what you did."
        to "برنامهٔ این روز نگه داشته نشده — مال پیش از زمانی است که برنامه‌ها ذخیره می‌شدند. با " +
        "فهرست زیر آنچه انجام دادید را اضافه کنید.",
    "One box per block of the day's plan. Open it, say how much of it you did, when you started " +
        "and how it went, and save. Blocks you leave unreported teach the model nothing, which is " +
        "better than a guess."
        to "برای هر بلوک برنامهٔ روز یک باکس هست. بازش کنید، بگویید چقدرش را انجام دادید، کی شروع " +
        "کردید و چطور پیش رفت، و ذخیره کنید. بلوکی که گزارش نشود چیزی به مدل یاد نمی‌دهد، که از " +
        "حدس زدن بهتر است.",
    "Skipped · instead:" to "انجام نشد · به‌جایش:",
    "Skipped · nothing else done" to "انجام نشد · کار دیگری هم نشد",
    "What did you do instead?" to "به‌جایش چه کردید؟",
    "Nothing" to "هیچ کاری",
    "Pick every one you did — several can share this time. Each gets its own answers below, and " +
        "each teaches the model like a planned session."
        to "هر کدام را انجام دادید انتخاب کنید — چند کار می‌توانند در این زمان باشند. هر کدام " +
        "پاسخ‌های خودش را پایین دارد، و هر کدام مثل یک جلسهٔ برنامه‌ریزی‌شده به مدل یاد می‌دهد.",
    "Not in the plan" to "خارج از برنامه",
    "Anything you did that no block above covers. It teaches the model and counts toward your " +
        "goals just like a planned session."
        to "هر کاری که کردید و هیچ بلوکی در بالا آن را پوشش نمی‌دهد. درست مثل یک جلسهٔ " +
        "برنامه‌ریزی‌شده به مدل یاد می‌دهد و در پیشرفت اهداف حساب می‌شود.",
    "Add something you did" to "افزودن کاری که انجام دادید",
    "Not saved yet" to "هنوز ذخیره نشده",
    "{0}% of target · focus {1}/{2} · drained {3}/{4}"
        to "{0}٪ از هدف · تمرکز {1}/{2} · خستگی {3}/{4}",
    "restored {0}/{1}" to "بازیابی {0}/{1}",
    "Your entries changed the model. The Horizon tab says what, and offers a rebuild."
        to "ورودی‌های شما مدل را تغییر دادند. زبانهٔ افق می‌گوید چه چیزی، و ساختن دوباره را پیشنهاد می‌دهد.",
    "Your entries were taken in. The model did not move enough to change the plan."
        to "ورودی‌های شما وارد شدند. مدل آن‌قدر تغییر نکرد که برنامه عوض شود.",
    "Your log changed the model" to "گزارش شما مدل را تغییر داد",
    "The entries you applied moved these estimates, so the plan was built on old numbers. Build " +
        "the plan to use the new ones, or leave it to tonight's rebuild."
        to "ورودی‌هایی که اعمال کردید این برآوردها را جابه‌جا کردند، پس برنامه روی عددهای قدیمی " +
        "ساخته شده است. برای استفاده از عددهای جدید «ساختن برنامه» را بزنید، یا بگذارید بازسازی " +
        "امشب انجامش دهد.",

    // ------------------------------------------------------------- windows
    "This window ends before it starts, so it is ignored until you fix it. To run until midnight, " +
        "end it at {0}:{1}."
        to "این پنجره پیش از شروعش تمام می‌شود، پس تا درستش نکنید در نظر گرفته نمی‌شود. برای این‌که " +
        "تا نیمه‌شب باشد، پایانش را {0}:{1} بگذارید.",
    "{}: the window {} ends before it starts, so it is ignored until you fix it"
        to "{}: پنجرهٔ {} پیش از شروعش تمام می‌شود، پس تا درستش نکنید در نظر گرفته نمی‌شود",

    // --------------------------------------------------------- sleep and nap
    "Sleep" to "خواب",
    "Nap" to "چرت",
    "Afternoon nap" to "چرت نیم‌روزی",
    "I take a nap" to "چرت می‌زنم",
    "Planned like an activity: on the days you choose, inside its window or at a fixed time."
        to "مثل یک فعالیت برنامه‌ریزی می‌شود: در روزهایی که انتخاب می‌کنید، داخل پنجره‌اش یا در یک ساعت ثابت.",
    "Days" to "روزها",
    "How it is placed" to "چطور چیده شود",
    "The planner picks the time in its window" to "برنامه‌ریز زمانش را داخل پنجره انتخاب کند",
    "At a fixed time" to "در یک ساعت ثابت",
    "At" to "ساعت",
    "Shortest (min)" to "کوتاه‌ترین (دقیقه)",
    "Longest (min)" to "بلندترین (دقیقه)",
    "The plan books the shortest length, so the nap never crowds out the day; sleep longer when you can."
        to "برنامه کوتاه‌ترین مدت را رزرو می‌کند تا چرت جای بقیهٔ روز را تنگ نکند؛ هر وقت توانستید بیشتر بخوابید.",
    "How much of the day's fatigue it takes away. A good nap is among the most restoring things you " +
        "do; {0} is a fair start, and your Log refines it."
        to "چه مقدار از خستگی روز را برمی‌دارد. یک چرت خوب از بازیابی‌کننده‌ترین کارهای شماست؛ {0} " +
        "شروع مناسبی است و گزارش‌هایتان دقیق‌ترش می‌کنند.",
    "Put sleep in the calendar" to "خواب را هم در تقویم بگذار",
    "The night's sleep, and the nap if you take one, as blocks — so the calendar shows the whole day."
        to "خواب شب، و چرت اگر دارید، به‌صورت بلوک — تا تقویم کل روز را نشان دهد.",

    // --------------------------------------------- what a day could not hold
    "Not everything fits in the coming days" to "در روزهای آینده همه‌چیز جا نمی‌شود",
    "Worth knowing about the coming days" to "دربارهٔ روزهای آینده بهتر است بدانید",
    "Read from the arranged days themselves. Each line says why, and what would fix it."
        to "از خود روزهای چیده‌شده خوانده شده. هر خط می‌گوید چرا، و چه چیزی درستش می‌کند.",
    "Not placed in this day's plan" to "در برنامهٔ این روز جا نگرفت",
    "{0} item(s) did not fit" to "{0} مورد جا نشد",
    "its pinned time {} is taken by {}; move the pin, or remove it for this weekday"
        to "ساعت ثابتش ({}) را {} گرفته است؛ ساعت ثابت را جابه‌جا کنید، یا برای این روز هفته برش دارید",
    "its pinned time {} is taken by {}, and no other free stretch inside its allowed hours was long " +
        "enough{}; move the pin, or widen its window"
        to "ساعت ثابتش ({}) را {} گرفته است و در ساعت‌های مجازش هم فضای خالی کافی نبود{}؛ ساعت ثابت را " +
        "جابه‌جا کنید، یا پنجره‌اش را بازتر کنید",
    "no free stretch inside its allowed hours was long enough for it plus its {} minutes of changeover; " +
        "widen a window, or shorten the changeover"
        to "در ساعت‌های مجازش فضای خالی کافی برای خودش و {} دقیقه زمان آماده‌شدن نبود؛ پنجره را بازتر کنید، " +
        "یا زمان آماده‌شدن را کوتاه‌تر",
    "no free stretch of {} minutes was left inside its allowed hours{}; widen a window, or move something out of it"
        to "در ساعت‌های مجازش {} دقیقه فضای خالی نماند{}؛ پنجره را بازتر کنید، یا چیزی را از آن بیرون ببرید",
    "pushed out by {}, which has to happen then, and no other free stretch inside its allowed hours was " +
        "long enough; widen its window"
        to "{} که باید همان موقع انجام شود جایش را گرفت، و در ساعت‌های مجازش فضای دیگری نبود؛ پنجره‌اش را بازتر کنید",
    "{} starts at {} with no time to get into it: {} runs right up to it. Pin it {} minutes later, or " +
        "shorten its changeover."
        to "{} ساعت {} شروع می‌شود بدون فرصتی برای آماده‌شدن: {} درست تا همان لحظه ادامه دارد. ساعت ثابتش را " +
        "{} دقیقه دیرتر بگذارید، یا زمان آماده‌شدنش را کوتاه‌تر کنید.",
    "{} could not be at its pinned {} because {} is there, so it moved to {}. Move the pin, or remove it " +
        "for this weekday."
        to "{} نتوانست در ساعت ثابتش ({}) باشد چون {} آن‌جاست، پس به {} منتقل شد. ساعت ثابت را جابه‌جا " +
        "کنید، یا برای این روز هفته برش دارید.",
    "{} could not be at its pinned {}, so it moved to {}. Move the pin, or remove it for this weekday."
        to "{} نتوانست در ساعت ثابتش ({}) باشد، پس به {} منتقل شد. ساعت ثابت را جابه‌جا کنید، یا برای این " +
        "روز هفته برش دارید.",
    "{} was pushed out by {} and moved to {}." to "{} به خاطر {} جابه‌جا شد و به {} رفت.",
    "{} was pushed out by {}; its {}h roll into the horizon."
        to "{} به خاطر {} کنار رفت؛ {} ساعتش به روزهای بعد منتقل می‌شود.",
    "{} had to move to {} because {} is at its usual time."
        to "{} مجبور شد به {} برود چون {} در ساعت همیشگی‌اش است.",

    // ------------------------------------------------------ the day's entries
    "Entries for this day" to "ورودی‌های این روز",
    "Nothing logged for this day yet." to "هنوز چیزی برای این روز ثبت نشده.",

    // ------------------------------------------------------- the trip, and export
    "Left at" to "ساعت حرکت",
    "How drained on arrival" to "چقدر خسته رسیدید",
    "teaches this trip's fatigue load" to "بار خستگی این مسیر را به مدل می‌آموزد",
    "What did you do on the way?" to "در راه چه کردید؟",
    "Reading, English in your ears, planning the day — whatever you did while travelling counts toward " +
        "its goal and teaches the model, like a session at the desk."
        to "مطالعه، گوش دادن به انگلیسی، برنامه‌ریزی روز — هر کاری در راه کردید در پیشرفت هدفش حساب " +
        "می‌شود و به مدل یاد می‌دهد، درست مثل یک جلسه پشت میز.",
    "drained {0}/{1}" to "خستگی {0}/{1}",
    "on the way:" to "در راه:",
    "{} — {} min, drained {}/5" to "{} — {} دقیقه، خستگی {}/۵",
    "fatigue load {0} to {1} ({2} trips)" to "بار خستگی از {0} به {1} ({2} سفر)",
    "Save everything to a file" to "ذخیرهٔ همه‌چیز در یک فایل",
    "Every log entry since the start, the model's coefficients as they are now, and each change the " +
        "model made to them — one JSON file, saved wherever you choose."
        to "همهٔ ورودی‌های لاگ از ابتدا، ضرایب فعلی مدل، و هر تغییری که مدل در آن‌ها داده — در یک فایل " +
        "JSON، هر جا که خودتان انتخاب کنید.",
    "Choose where to save" to "انتخاب محل ذخیره",
    "Saved: every log entry, the model's coefficients and how they changed."
        to "ذخیره شد: همهٔ ورودی‌های لاگ، ضرایب مدل و روند تغییرشان.",
    "That location cannot be written to." to "در آن محل نمی‌شود نوشت.",
    "Every goal and activity in full — settings, learned values, what the log says about each and how " +
        "it changed — plus every log entry since the start and every change the model made. One JSON " +
        "file, saved wherever you choose."
        to "همهٔ اهداف و فعالیت‌ها به‌طور کامل — تنظیمات، مقادیر یادگرفته‌شده، آنچه لاگ دربارهٔ هر کدام " +
        "می‌گوید و روند تغییرش — به‌همراه همهٔ ورودی‌های لاگ از ابتدا و هر تغییری که مدل داده. یک فایل " +
        "JSON، هر جا که خودتان انتخاب کنید.",
    "Saved: every goal, activity and log entry, and how the model changed."
        to "ذخیره شد: همهٔ اهداف، فعالیت‌ها و ورودی‌های لاگ، و روند تغییر مدل.",

    // ------------------------------------------------------ check-in and rest
    "How are you today?" to "امروز حالتان چطور است؟",
    "Morning" to "صبح",
    "Evening" to "شب",
    "Not answered yet" to "هنوز پاسخ داده نشده",
    "Energy {0}/{1} · day felt {2}/{3}" to "انرژی {0}/{1} · سنگینی روز {2}/{3}",
    "Energy {0}/{1} · {2} signs" to "انرژی {0}/{1} · {2} نشانه",
    "Energy {0}/{1} · day felt {2}/{3} · {4} signs" to "انرژی {0}/{1} · سنگینی روز {2}/{3} · {4} نشانه",
    "How much energy do you have?" to "چقدر انرژی دارید؟",
    "{0} empty  to  {1} full" to "{0} خالی  تا  {1} پر",
    "How heavy did today feel?" to "امروز چقدر سنگین بود؟",
    "{0} light  to  {1} crushing" to "{0} سبک  تا  {1} طاقت‌فرسا",
    "Is any of this true?" to "کدام‌یک از این‌ها درست است؟",
    "Slept badly" to "بد خوابیدم",
    "Woke up exhausted" to "خسته از خواب بیدار شدم",
    "Feeling unwell" to "حالم خوب نیست / ناخوشم",
    "Body aches or headache" to "بدن‌درد یا سردرد",
    "Low mood" to "حالم گرفته است",
    "Anxious or stressed" to "مضطرب یا تحت فشارم",
    "No motivation at all" to "اصلاً انگیزه ندارم",
    "Emotionally drained" to "از نظر احساسی تخلیه شده‌ام",
    "Something happened" to "اتفاقی افتاده است",
    "Overwhelmed" to "از پا درآمده‌ام",
    "Mentally exhausted" to "ذهنم خسته است",
    "The signs say rest" to "نشانه‌ها می‌گویند استراحت کنید",
    "Worth considering a rest day" to "ارزش دارد یک روز استراحت را در نظر بگیرید",
    "Nothing says you need to stop" to "چیزی نشان نمی‌دهد که باید دست نگه دارید",
    "Make today a rest day" to "امروز را روز استراحت کن",
    "I need a rest day anyway" to "به هر حال به یک روز استراحت نیاز دارم",
    "Back to the normal plan" to "بازگشت به برنامهٔ عادی",
    "Today is a rest day." to "امروز روز استراحت است.",
    "Today is a rest day" to "امروز روز استراحت است",
    "Rest day" to "روز استراحت",
    "Goal work is paused; upkeep and your rest activities are planned. Tomorrow morning's check-in " +
        "decides whether to come back, and the return is gradual — deadlines are caught up over the next " +
        "days, not all at once."
        to "کار روی اهداف متوقف است؛ کارهای روزمره و فعالیت‌های استراحتی شما برنامه‌ریزی شده‌اند. " +
        "ثبت وضعیت صبح فردا تصمیم می‌گیرد که برگردید یا نه، و بازگشت تدریجی است — عقب‌افتادگی " +
        "مهلت‌ها در روزهای بعد جبران می‌شود، نه یک‌باره.",
    "Goal work is paused today. The days after it come back gradually, as far as the deadlines allow."
        to "امروز کار روی اهداف متوقف است. روزهای بعد، تا جایی که مهلت‌ها اجازه دهند، تدریجی برمی‌گردند.",
    "Goal work is paused; only upkeep and rest activities are planned."
        to "کار روی اهداف متوقف است؛ فقط کارهای روزمره و فعالیت‌های استراحتی برنامه‌ریزی شده‌اند.",
    "Switch to rest by itself when the signs are clear" to "وقتی نشانه‌ها روشن‌اند خودکار به حالت استراحت برود",
    "Off, it only suggests. Either way you can take the day back."
        to "اگر خاموش باشد فقط پیشنهاد می‌دهد. در هر حال می‌توانید روز را برگردانید.",
    "This week's load" to "بار این هفته",
    "{0}% of a normal week ({1} against {2} load-hours). Above {3}% the week is heavy; above {4}% it is " +
        "the kind that ends in a crash."
        to "{0}٪ یک هفتهٔ عادی ({1} در برابر {2} ساعتِ بار). بالای {3}٪ هفته سنگین است؛ بالای {4}٪ از آن " +
        "هفته‌هایی است که به فروپاشی ختم می‌شود.",
    "{0} load-hours in the last seven days: hours of work, classes and travel, each weighted by how " +
        "draining it is. Comparing it with a normal week needs two weeks of logged days; you have {1}."
        to "{0} ساعتِ بار در هفت روز گذشته: ساعت‌های کار، کلاس و رفت‌وآمد، هر کدام به اندازهٔ خسته‌کنندگی‌اش. " +
        "مقایسه با یک هفتهٔ عادی دو هفته روزِ ثبت‌شده لازم دارد؛ شما {1} روز دارید.",
    "you woke with almost no energy" to "تقریباً بدون انرژی بیدار شدید",
    "your energy this morning is low" to "انرژی امروز صبحتان کم است",
    "this morning: {}" to "امروز صبح: {}",
    "last night: {}" to "دیشب: {}",
    "you ended yesterday with little energy left" to "دیروز را با انرژی کمی تمام کردید",
    "yesterday felt heavy" to "دیروز سنگین بود",
    "this week's load is {0}% of a normal week" to "بار این هفته {0}٪ یک هفتهٔ عادی است",
    "your mornings have been low for three days running" to "سه روز پشت‌سرهم صبح‌ها کم‌انرژی بوده‌اید",
    "The signs are clear, so today is now a rest day. Goal work is paused; tomorrow morning decides again."
        to "نشانه‌ها روشن‌اند، پس امروز روز استراحت شد. کار روی اهداف متوقف است؛ صبح فردا دوباره تصمیم گرفته می‌شود.",
    "Today is a rest day. Goal work is paused and the plan was rebuilt."
        to "امروز روز استراحت است. کار روی اهداف متوقف شد و برنامه دوباره ساخته شد.",
    "Back to the normal plan for today." to "امروز به برنامهٔ عادی برگشت.",
    "Only on rest days" to "فقط در روزهای استراحت",
    "A walk, a film, seeing family: planned on rest days alongside the usual upkeep, and never on a normal day."
        to "پیاده‌روی، فیلم، دیدن خانواده: در روزهای استراحت کنار کارهای روزمره برنامه‌ریزی می‌شود، و هرگز در روز عادی.",

    // --------------------------------------------------- navigator
    "Navigator"
        to "ناوبری",
    "Your goal graph: what depends on what, who owns it, and when it is due. The layers that forecast and allocate hours work from it."
        to "گراف اهداف تو: چه چیزی به چه چیزی وابسته است، مالکش کیست و مهلتش کی است. لایه‌هایی که پیش‌بینی می‌کنند و ساعت‌ها را تخصیص می‌دهند از روی همین کار می‌کنند.",
    "Goal graph"
        to "گراف اهداف",
    "No goal graph yet. Import the goal-graph JSON file (for example goal-graph-v0.2.json). It is checked first: a file with a loop, a missing node or a broken consent rule is refused whole."
        to "هنوز گراف اهدافی نیست. فایل JSON گراف اهداف را وارد کن (مثلاً goal-graph-v0.2.json). اول بررسی می‌شود: فایلی که دور، گره‌ی ناموجود یا قید رضایت شکسته داشته باشد کامل رد می‌شود.",
    "Version"
        to "نسخه",
    "Imported"
        to "واردشده در",
    "{0} nodes, {1} edges, {2} regimes"
        to "{0} گره، {1} یال، {2} رژیم",
    "Import goal graph"
        to "وارد کردن گراف اهداف",
    "Import a new version"
        to "وارد کردن نسخه‌ی جدید",
    "A new version replaces the nodes and the calendar. What the log taught and the answers you gave here are kept, unless the new file states them itself."
        to "نسخه‌ی جدید گره‌ها و تقویم را جایگزین می‌کند. آنچه از لاگ یاد گرفته شده و پاسخ‌هایی که این‌جا دادی می‌مانند، مگر این‌که فایل جدید خودش آن‌ها را مشخص کرده باشد.",
    "The file was not imported"
        to "فایل وارد نشد",
    "The file was not imported."
        to "فایل وارد نشد.",
    "Goal graph imported."
        to "گراف اهداف وارد شد.",
    "The file could not be read."
        to "فایل خوانده نشد.",
    "Nothing was changed. Fix these in the file and import it again."
        to "هیچ چیز تغییر نکرد. این‌ها را در فایل درست کن و دوباره واردش کن.",
    "Nothing is missing"
        to "چیزی کم نیست",
    "Every number the layers need has a source."
        to "هر عددی که لایه‌ها لازم دارند منبع دارد.",
    "{0} things are missing"
        to "{0} مورد کم است",
    "The Navigator will not compute on a number nobody gave. Answer these below; each answer is saved as yours and can be changed later."
        to "ناوبر روی عددی که کسی نداده حساب نمی‌کند. این‌ها را پایین جواب بده؛ هر پاسخ به نام خودت ذخیره می‌شود و بعداً قابل تغییر است.",
    "Missing data"
        to "داده‌های ناقص",
    "Close"
        to "بستن",
    "Answer this"
        to "پاسخ بده",
    "There are no goal activities in Chronos yet. Create one in Setup."
        to "هنوز فعالیت هدف‌داری در Chronos نیست. در تنظیمات یکی بساز.",
    "Count this Chronos activity instead"
        to "به‌جایش این فعالیت Chronos را حساب کن",
    "Or create an activity with exactly this name in Setup → Activities; it is found by name."
        to "یا در تنظیمات ← فعالیت‌ها فعالیتی دقیقاً با همین نام بساز؛ از روی نام پیدا می‌شود.",
    "Link"
        to "وصل کن",
    "Total hours of work. The median is your honest guess; the 90th percentile is the figure you would be surprised to exceed."
        to "کل ساعت کار. میانه حدس صادقانه‌ی توست؛ صدک ۹۰ عددی است که اگر از آن بیشتر شود تعجب می‌کنی.",
    "Median (hours)"
        to "میانه (ساعت)",
    "90th percentile (hours)"
        to "صدک ۹۰ (ساعت)",
    "The 90th percentile must be at least the median."
        to "صدک ۹۰ باید دست‌کم برابر میانه باشد.",
    "How likely is it, and how sure are you of that? Sureness is counted in cases: 10 means \"as if I had seen ten similar cases\"; 2 means a loose guess."
        to "چقدر محتمل است، و چقدر از این مطمئنی؟ اطمینان با تعداد مورد سنجیده می‌شود: ۱۰ یعنی «انگار ده مورد مشابه دیده‌ام»؛ ۲ یعنی حدسی سست.",
    "Probability (%)"
        to "احتمال (٪)",
    "Sureness (cases)"
        to "اطمینان (تعداد مورد)",
    "Suggested"
        to "پیشنهاد",
    "Use the suggestions"
        to "پیشنهادها را بگذار",
    "Name the stretch (for example TERM2) and give its dates. You can split a long stretch: save the first part, and what is left will be asked again."
        to "برای این بازه نامی بگذار (مثلاً TERM2) و تاریخ‌هایش را بده. می‌توانی بازه‌ی بلند را تکه کنی: بخش اول را ذخیره کن، باقی‌مانده دوباره پرسیده می‌شود.",
    "To"
        to "تا",
    "Of every hundred days in this stretch, how many are ideal, busy and stressful?"
        to "از هر صد روزِ این بازه، چند روز ایده‌آل، چند روز شلوغ و چند روز پرتنش است؟",
    "Ideal days (%)"
        to "روزهای ایده‌آل (٪)",
    "Busy days (%)"
        to "روزهای شلوغ (٪)",
    "Stress days (%)"
        to "روزهای پرتنش (٪)",
    "A regime with this name already exists."
        to "رژیمی با این نام از قبل هست.",
    "The three must add up to 100."
        to "جمع این سه باید ۱۰۰ شود.",
    "Graph"
        to "گراف",
    "Every node by phase. Tap one for its dates, effort and what it depends on."
        to "همه‌ی گره‌ها به ترتیب فاز. روی هرکدام بزن تا تاریخ‌ها، تلاش و وابستگی‌هایش را ببینی.",
    "Phase"
        to "فاز",
    "Stream"
        to "جریان",
    "Skill"
        to "مهارت",
    "Task"
        to "کار",
    "Milestone"
        to "نقطه‌ی عطف",
    "Gate"
        to "دروازه",
    "Decision"
        to "تصمیم",
    "Final goal"
        to "هدف نهایی",
    "External track"
        to "مسیر بیرونی",
    "advisor"
        to "استاد",
    "you"
        to "خودت",
    "suggested"
        to "پیشنهادی",
    "outside"
        to "بیرونی",
    "paused now"
        to "فعلاً متوقف",
    "due"
        to "مهلت",
    "Not before"
        to "نه قبل از",
    "To be broken down on"
        to "شکسته می‌شود در",
    "Effort"
        to "تلاش",
    "hours (median to 90th percentile)"
        to "ساعت (میانه تا صدک ۹۰)",
    "Units"
        to "واحدها",
    "Level"
        to "سطح",
    "Weekly floor"
        to "کف هفتگی",
    "at most"
        to "حداکثر",
    "day"
        to "روز",
    "Chance of success"
        to "شانس موفقیت",
    "Chronos activities"
        to "فعالیت‌های Chronos",
    "Only through"
        to "فقط از راه",
    "Paused during"
        to "متوقف در",
    "Needs"
        to "نیاز دارد به",
    "Needs one of"
        to "نیاز به یکی از",
    "Helped by"
        to "کمک می‌گیرد از",
    "Feeds"
        to "تغذیه می‌کند",
    "Parameters"
        to "پارامترها",
    "Every number the layers read, with where it came from. Nothing here is a hidden default."
        to "هر عددی که لایه‌ها می‌خوانند، با منبعش. هیچ‌کدام پیش‌فرض پنهان نیست.",
    "Show all"
        to "نمایش همه",
    "from you"
        to "از خودت",
    "learned from the log"
        to "یادگرفته از لاگ",
    "proposed by the LLM, approved by you"
        to "پیشنهاد LLM، تأییدشده توسط تو",
    "stated assumption"
        to "فرض صریح",
    "{} has no effort estimate (hours at the median and at the 90th percentile)"
        to "{} برآورد تلاش ندارد (ساعت در میانه و در صدک ۹۰)",
    "{} has no current or target level"
        to "{} سطح فعلی یا هدف ندارد",
    "{} has no estimate of hours per level"
        to "{} برآورد ساعت برای هر سطح ندارد",
    "{} has no weekly floor"
        to "{} کف هفتگی ندارد",
    "{} has no success prior: how likely is it to open?"
        to "{} احتمال پیشین موفقیت ندارد: چقدر محتمل است باز شود؟",
    "{} depends on nothing in the graph and has no condition, so nothing decides whether it is reached. Give it a probability (success prior)."
        to "{} به هیچ چیز در گراف وابسته نیست و شرطی هم ندارد، پس چیزی تعیین نمی‌کند که رسیده یا نه. به آن یک احتمال (پیشین موفقیت) بده.",
    "{} counts the Chronos activity \"{}\", which does not exist. Create it in Setup or link an existing one. Not needed yet: the node is paused or has not started."
        to "{} فعالیت «{}» در Chronos را حساب می‌کند که وجود ندارد. در تنظیمات بسازش یا به فعالیتی موجود وصلش کن. فعلاً لازم نیست: گره متوقف است یا هنوز شروع نشده.",
    "{} counts the Chronos activity \"{}\", which does not exist. Create it in Setup or link an existing one."
        to "{} فعالیت «{}» در Chronos را حساب می‌کند که وجود ندارد. در تنظیمات بسازش یا به فعالیتی موجود وصلش کن.",
    "{} has no mix of ideal, busy and stress days"
        to "{} ترکیب روزهای ایده‌آل، شلوغ و پرتنش ندارد",
    "{} ({} to {}) was an assumption. Are these dates right?"
        to "{} ({} تا {}) یک فرض بود. این تاریخ‌ها درست است؟",
    "No regime covers {} to {}: the forecast does not know what kind of days these are"
        to "هیچ رژیمی {} تا {} را پوشش نمی‌دهد: پیش‌بینی نمی‌داند این روزها چه نوعی‌اند",
    "Parameter {} has no value for {}"
        to "پارامتر {} برای {} مقدار ندارد",
    "Each planned session is a trial — done or skipped. Beta(a, b) is your prior on the share done."
        to "هر جلسه‌ی برنامه‌ریزی‌شده یک آزمایش است — انجام‌شده یا ردشده. Beta(a, b) پیشینِ تو روی سهم انجام‌شده‌هاست.",
    "Each week old evidence is multiplied by this, so the model follows habits that change."
        to "هر هفته شواهد قدیمی در این عدد ضرب می‌شوند تا مدل عادت‌های در حال تغییر را دنبال کند.",
    "How much longer than planned a new kind of work takes, before the log says anything about it."
        to "یک نوع کار جدید چقدر بیشتر از برنامه طول می‌کشد، پیش از آن‌که لاگ چیزی درباره‌اش بگوید.",
    "Spread of one session's log(actual / planned) around the class's true bias."
        to "پراکندگی log(واقعی / برنامه) یک جلسه دور سوگیری واقعی آن دسته.",
    "Goal hours on a {} day. mean is your prior; tau0 is how unsure that prior is; sigma is how much one such day varies around it (all in hours)."
        to "ساعت هدف‌دار در یک روز {}. mean پیشینِ توست؛ tau0 یعنی چقدر به آن پیشین نامطمئنی؛ sigma یعنی یک روز از این نوع چقدر دور آن نوسان دارد (همه به ساعت).",
    "An hour's average energy is pulled toward your overall average as if by this many extra check-ins."
        to "میانگین انرژی هر ساعت به سمت میانگین کلی تو کشیده می‌شود، انگار به این تعداد چک‌این اضافه.",
    "How many days the stated mix of a regime counts for before your ticked day types take over."
        to "ترکیب اعلام‌شده‌ی هر رژیم به اندازه‌ی چند روز حساب می‌شود، پیش از آن‌که نوع روزهایی که تیک می‌زنی جایش را بگیرند.",
    "How much one logged day counts in a regime that does not set its own data weight."
        to "در رژیمی که وزن داده‌ی خودش را تعیین نکرده، هر روزِ ثبت‌شده چقدر حساب می‌شود.",
    "{} refers to {}, which is not in the graph"
        to "{} به {} اشاره می‌کند که در گراف نیست",
    "{} depends on itself"
        to "{} به خودش وابسته است",
    "The graph has a loop: {}"
        to "گراف دور دارد: {}",
    "{} is paused during {}, which is not a regime"
        to "{} در {} متوقف است که رژیم نیست",
    "{} has parent {}, which is not an external track"
        to "والدِ {} {} است که مسیر بیرونی نیست",
    "The consent policy names {}, which is not in the graph"
        to "سیاست رضایت از {} نام می‌برد که در گراف نیست",
    "{} overlays {}, which is not a regime"
        to "{} روی {} سوار است که رژیم نیست",
    "{} plans hours for {}, which is not in the graph"
        to "{} برای {} ساعت برنامه‌ریزی کرده که در گراف نیست",
    "{} is covered by the consent policy but does not name its channel"
        to "{} زیر سیاست رضایت است اما کانالش را نام نبرده",
    "{} involves another person but names no channel"
        to "{} به فرد دیگری مربوط است اما کانالی نام نبرده",
    "{} cannot be planned, so {} may not sit under it"
        to "{} برنامه‌ریزی‌پذیر نیست، پس {} نمی‌تواند زیرش باشد",
    "{} cannot be planned, so it cannot be work that takes hours"
        to "{} برنامه‌ریزی‌پذیر نیست، پس نمی‌تواند کاری باشد که ساعت می‌گیرد",
    "{} ends before it starts"
        to "{} قبل از شروعش تمام می‌شود",
    "{} and {} overlap"
        to "{} و {} هم‌پوشانی دارند",
    "{}: the shares of day types add up to {}, not 1"
        to "{}: جمع سهم نوع روزها {} است، نه ۱",
    "{}: {} is above {}"
        to "{}: {} از {} بیشتر است",
    "{}: {} must be above zero"
        to "{}: {} باید بیشتر از صفر باشد",
    "{}: {} cannot be negative"
        to "{}: {} نمی‌تواند منفی باشد",
    "{}: levels run 0 to 10"
        to "{}: سطح‌ها از ۰ تا ۱۰ است",
    "{}: level from is above level to"
        to "{}: سطح فعلی از سطح هدف بالاتر است",
    "{}: the weekly floor cannot fit under the daily cap"
        to "{}: کف هفتگی زیر سقف روزانه جا نمی‌شود",
    "{}: earliest start is after the deadline"
        to "{}: زودترین شروع بعد از مهلت است",
    "{}: {} is past the graph's horizon {}"
        to "{}: {} بعد از افق گراف ({}) است",
    "Two nodes share the id {}"
        to "دو گره شناسه‌ی {} را دارند",
    "The file is not valid JSON: {}"
        to "فایل JSON معتبر نیست: {}",
    "The file has no nodes"
        to "فایل هیچ گره‌ای ندارد",

    // ------------------------------------------ navigator, round 2
    "The activities that move your goals forward — at least one per goal. Weekdays, allowed hours, fatigue load, recovery, and if it should wait, the day it starts."
        to "فعالیت‌هایی که اهدافت را جلو می‌برند — دست‌کم یکی برای هر هدف. روزهای هفته، ساعت‌های مجاز، بار خستگی، بازیابی، و اگر باید صبر کند، روز شروعش.",
    "Meals, prayer, exercise, chores: what earns no progress but keeps you able to work. Same settings as goal work; set up here so the two lists stay apart."
        to "غذا، نماز، ورزش، کارهای خانه: چیزهایی که پیشرفتی نمی‌آورند ولی توان کار را نگه می‌دارند. همان تنظیمات کار هدف‌دار را دارند؛ این‌جا جدا ساخته می‌شوند تا دو فهرست قاطی نشوند.",
    "An activity is how a goal gets worked on: each one serves one goal."
        to "فعالیت راهی است که روی یک هدف کار می‌شود: هرکدام به یک هدف خدمت می‌کند.",
    "Upkeep earns nothing but restores capacity, which is why lunch pays for itself."
        to "نگهداری چیزی به دست نمی‌آورد اما توان را برمی‌گرداند؛ برای همین ناهار هزینه‌ی خودش را درمی‌آورد.",
    "Kind is not set: is this goal work or upkeep?"
        to "نوعش مشخص نیست: کار هدف‌دار است یا نگهداری؟",
    "Add a goal in the Goals step first; goal work has to serve one."
        to "اول در مرحله‌ی اهداف یک هدف اضافه کن؛ کار هدف‌دار باید به یک هدف خدمت کند.",
    "Move to upkeep"
        to "انتقال به نگهداری",
    "Move to goal work"
        to "انتقال به کار هدف‌دار",
    "Add goal work"
        to "افزودن کار هدف‌دار",
    "Add upkeep"
        to "افزودن نگهداری",
    "New upkeep"
        to "نگهداری جدید",
    "Starts on"
        to "شروع از",
    "From now"
        to "از همین حالا",
    "Planned from now. Pick a date to keep it out of the plan until then."
        to "از همین حالا برنامه‌ریزی می‌شود. اگر تاریخی انتخاب کنی، تا آن روز در برنامه نمی‌آید.",
    "This start is not before the goal's deadline"
        to "این شروع قبل از مهلت هدف نیست",
    "The planner stays locked until it is moved earlier."
        to "تا زودتر نشود، برنامه‌ریز قفل می‌ماند.",
    "Not planned before this date; its goal's hours are spread over the days from then on."
        to "قبل از این تاریخ برنامه‌ریزی نمی‌شود؛ ساعت‌های هدفش روی روزهای بعد از آن پخش می‌شود.",
    "{}: starts on {}, which is not before its goal's deadline {}; move the start earlier"
        to "{}: از {} شروع می‌شود که قبل از مهلت هدفش ({}) نیست؛ شروع را زودتر کن",
    "One page per phase. The colour of a card says what kind of node it is; tap a card for its details, and tap a node named there to go to it."
        to "هر فاز یک صفحه. رنگ هر کارت نوع گره را نشان می‌دهد؛ روی کارت بزن تا جزئیاتش باز شود، و روی گره‌ای که آن‌جا نام برده شده بزن تا به آن بروی.",
    "What do the kinds mean?"
        to "این نوع‌ها یعنی چه؟",
    "Dates in this phase run from"
        to "تاریخ‌های این فاز از",
    "days"
        to "روز",
    "Decide by"
        to "تصمیم تا",
    "Leads to"
        to "منجر می‌شود به",
    "or"
        to "یا",
    "Ongoing work with no end, paced by hours per week (coursework, a language)."
        to "کاری همیشگی و بی‌پایان که با ساعت در هفته تنظیم می‌شود (درس‌ها، یک زبان).",
    "A level from 0 to 10 you are raising; it finishes when the target level is reached."
        to "سطحی بین ۰ تا ۱۰ که داری بالا می‌بری؛ وقتی به سطح هدف برسد تمام می‌شود.",
    "Bounded work: it finishes once its hours are spent."
        to "کار محدود: وقتی ساعت‌هایش صرف شد تمام می‌شود.",
    "A checkpoint, reached when what it depends on is done."
        to "یک نقطه‌ی بازبینی که وقتی وابستگی‌هایش انجام شود به آن می‌رسی.",
    "An outcome someone else decides (an admission, an offer); it has a chance of success."
        to "نتیجه‌ای که دیگری تعیین می‌کند (پذیرش، پیشنهاد کاری)؛ یک شانس موفقیت دارد.",
    "A choice between options that you make on a set date."
        to "انتخابی بین چند گزینه که در تاریخی مشخص خودت انجام می‌دهی.",
    "The final goal at the end of the graph."
        to "هدف نهایی در انتهای گراف.",
    "A container of tasks your advisor assigns; scheduled, never restructured."
        to "ظرفی از کارهایی که استادت تعیین می‌کند؛ زمان‌بندی می‌شود ولی ساختارش هرگز تغییر نمی‌کند.",
    "What is this?"
        to "این چیست؟",
    "Comes after"
        to "بعد از",
    "What depends on it"
        to "چه چیزهایی به آن وابسته‌اند",
    "Why it is needed"
        to "چرا لازم است",
    "What a regime is"
        to "رژیم یعنی چه",
    "The forecast (stage 3) plays out the whole graph from today to its last date, thousands of times. A node that takes hours but has no estimate cannot be played out — and neither can anything that comes after it."
        to "پیش‌بینی (مرحله‌ی ۳) کل گراف را از امروز تا آخرین تاریخش هزاران بار شبیه‌سازی می‌کند. گره‌ای که ساعت می‌گیرد ولی برآورد ندارد شبیه‌سازی نمی‌شود — و هیچ چیزی هم که بعد از آن می‌آید.",
    "This is a coarse node: it will be broken into smaller tasks"
        to "این یک گره‌ی درشت است: به کارهای کوچک‌تر شکسته می‌شود",
    "on"
        to "در",
    "A rough range is enough now; make the 90th percentile generous."
        to "فعلاً یک بازه‌ی تقریبی کافی است؛ صدک ۹۰ را دست‌ودل‌باز بگیر.",
    "Nothing in the graph decides this node, so the forecast decides it by chance: in each simulated future it is reached with the probability you give. The sureness says how much that probability itself may be off."
        to "هیچ چیز در گراف این گره را تعیین نمی‌کند، پس پیش‌بینی آن را با شانس تعیین می‌کند: در هر آینده‌ی شبیه‌سازی‌شده با احتمالی که می‌دهی به آن می‌رسی. اطمینان می‌گوید خود این احتمال چقدر ممکن است اشتباه باشد.",
    "Sessions you log under this Chronos activity are how the Navigator sees progress on the node, and the weekly allocation sends the node's hours to it. Without the link the node's time cannot reach your day plan."
        to "جلسه‌هایی که زیر این فعالیت Chronos ثبت می‌کنی راهی است که ناوبر پیشرفت گره را می‌بیند، و تخصیص هفتگی ساعت‌های گره را به آن می‌فرستد. بدون این اتصال، وقت گره به برنامه‌ی روزانه‌ات نمی‌رسد.",
    "Read by the estimate layer (stage 2), every night, as the starting point that your log then corrects."
        to "لایه‌ی تخمین (مرحله‌ی ۲) هر شب آن را به‌عنوان نقطه‌ی شروع می‌خواند و بعد لاگ تو اصلاحش می‌کند.",
    "A stretch of the calendar with its own share of ideal, busy and stressful days (term, exams, holidays). The forecast draws each future day's type from it to know how many hours you will have."
        to "بازه‌ای از تقویم با سهم خودش از روزهای ایده‌آل، شلوغ و پرتنش (ترم، امتحانات، تعطیلات). پیش‌بینی نوع هر روز آینده را از روی آن می‌کشد تا بداند چند ساعت خواهی داشت.",
    "Far ahead nobody knows this precisely. Give a rough mix with low sureness, or ask the LLM: it proposes stretches with a wide uncertainty, and the forecast treats them as rough."
        to "برای آینده‌ی دور هیچ‌کس این را دقیق نمی‌داند. یک ترکیب تقریبی با اطمینان کم بده، یا از LLM بپرس: بازه‌هایی با عدم قطعیت زیاد پیشنهاد می‌دهد و پیش‌بینی هم آن‌ها را تقریبی حساب می‌کند.",
    "Sureness (days)"
        to "اطمینان (روز)",
    "How many observed days this mix is worth before your ticked day types take over: 10 for a term you know well, 2 for a guess years ahead."
        to "این ترکیب به اندازه‌ی چند روز مشاهده‌شده ارزش دارد پیش از آن‌که نوع روزهایی که تیک می‌زنی جایش را بگیرند: ۱۰ برای ترمی که خوب می‌شناسی، ۲ برای حدسی چند سال جلوتر.",
    "Ask the LLM to propose"
        to "از LLM پیشنهاد بخواه",
    "It reads only what this question needs — dates, milestones, the regimes you already have; never your log — and answers with a range and how sure it is. Its answer waits in the proposals inbox below until you approve it. Or answer it yourself here."
        to "فقط چیزی را می‌خواند که این سؤال لازم دارد — تاریخ‌ها، نقاط عطف، رژیم‌هایی که داری؛ هرگز لاگت را — و با یک بازه و میزان اطمینانش جواب می‌دهد. جوابش در صندوق پیشنهادها می‌ماند تا تأییدش کنی. یا خودت همین‌جا جواب بده.",
    "Proposals"
        to "پیشنهادها",
    "What the LLM proposed. Nothing here has changed anything yet: approve to save the values with the LLM as their source, or reject."
        to "آنچه LLM پیشنهاد داده. هنوز هیچ‌کدام چیزی را تغییر نداده‌اند: تأیید کن تا مقدارها با منبع LLM ذخیره شوند، یا رد کن.",
    "Nothing is waiting."
        to "چیزی منتظر نیست.",
    "Hide past proposals"
        to "پنهان کردن پیشنهادهای قبلی",
    "Past proposals"
        to "پیشنهادهای قبلی",
    "waiting for you"
        to "منتظر تو",
    "approved"
        to "تأییدشده",
    "rejected by you"
        to "ردشده توسط تو",
    "failed the checks"
        to "در بررسی‌ها رد شد",
    "Rejected by the checks"
        to "در بررسی‌ها رد شد",
    "Sources"
        to "منابع",
    "Checks passed"
        to "بررسی‌های گذرانده",
    "Noted"
        to "دیدم",
    "Approve"
        to "تأیید",
    "Reject"
        to "رد",
    "Regime"
        to "رژیم",
    "Mix of days"
        to "ترکیب روزها",
    "worth"
        to "به ارزش",
    "cases"
        to "مورد",
    "Replaces"
        to "جایگزین می‌کند",
    "Your advisor owns this node: raise it with them; it will not be changed here."
        to "مالک این گره استاد توست: با او مطرحش کن؛ این‌جا تغییری نمی‌کند.",
    "LLM"
        to "LLM",
    "Used only when you ask (and, later, when a trigger fires). It never sees your log, and what it says is a proposal you approve or reject."
        to "فقط وقتی تو بخواهی (و بعداً وقتی تریگری فعال شود) استفاده می‌شود. هرگز لاگت را نمی‌بیند و هرچه بگوید پیشنهادی است که تأیید یا رد می‌کنی.",
    "Spent this month"
        to "هزینه‌ی این ماه",
    "API key saved"
        to "کلید API ذخیره شده",
    "No API key yet"
        to "هنوز کلید API نیست",
    "Settings"
        to "تنظیمات",
    "Anthropic API key"
        to "کلید API آنتروپیک",
    "Made at console.anthropic.com → API keys. Stored encrypted with a key kept in the phone's Keystore."
        to "از console.anthropic.com ← API keys ساخته می‌شود. با کلیدی که در Keystore گوشی نگه داشته می‌شود رمزنگاری و ذخیره می‌شود.",
    "Save key"
        to "ذخیره‌ی کلید",
    "Remove key"
        to "حذف کلید",
    "Model"
        to "مدل",
    "per million tokens"
        to "برای هر میلیون توکن",
    "Monthly budget (dollars)"
        to "بودجه‌ی ماهانه (دلار)",
    "When a question would pass it, it is not sent."
        to "اگر سؤالی از آن رد شود، فرستاده نمی‌شود.",
    "Calls per day at most"
        to "حداکثر فراخوان در روز",
    "Recent calls"
        to "فراخوان‌های اخیر",
    "The API key was removed."
        to "کلید API حذف شد.",
    "The API key was saved, encrypted on this phone."
        to "کلید API رمزنگاری‌شده روی همین گوشی ذخیره شد.",
    "Import the goal graph first."
        to "اول گراف اهداف را وارد کن.",
    "Add your Anthropic API key in the LLM panel first."
        to "اول کلید API آنتروپیک را در بخش LLM وارد کن.",
    "No network: the question is queued and will be sent when you are online."
        to "اینترنت نیست: سؤال در صف ماند و وقتی آنلاین شوی فرستاده می‌شود.",
    "This would pass your monthly LLM budget: {0} of {1} dollars spent, this question costs about {2}."
        to "این از بودجه‌ی ماهانه‌ی LLM بیشتر می‌شود: {0} از {1} دلار خرج شده و این سؤال حدود {2} هزینه دارد.",
    "Today's LLM calls are used up ({0}). Try again tomorrow."
        to "سهمیه‌ی فراخوان‌های امروز LLM تمام شده ({0}). فردا دوباره امتحان کن.",
    "The LLM declined to answer this question."
        to "LLM از پاسخ به این سؤال خودداری کرد.",
    "A proposal is waiting in the inbox."
        to "یک پیشنهاد در صندوق منتظر است.",
    "The answer was rejected: {}"
        to "پاسخ رد شد: {}",
    "The LLM call failed: {}"
        to "فراخوان LLM ناموفق بود: {}",
    "Approved: the values are saved with the LLM as their source."
        to "تأیید شد: مقدارها با منبع LLM ذخیره شدند.",
    "Rejected."
        to "رد شد.",

    // -------------------------------------- sleep, tags, providers
    "Last night's sleep"
        to "خواب دیشب",
    "The night before this day"
        to "شبِ قبل از این روز",
    "Not logged yet"
        to "هنوز ثبت نشده",
    "In bed"
        to "در رختخواب",
    "asleep about"
        to "خواب حدود",
    "restful"
        to "آرامش‌بخشی",
    "woke"
        to "بیدار شدن",
    "Went to bed"
        to "زمان خوابیدن",
    "Got up"
        to "زمان بیدار شدن",
    "Same as usual"
        to "مثل همیشه",
    "Minutes to fall asleep"
        to "دقیقه تا خواب رفتن",
    "Times you woke"
        to "دفعات بیدار شدن",
    "How restful was it?"
        to "چقدر آرامش‌بخش بود؟",
    "barely slept"
        to "تقریباً نخوابیدم",
    "deeply rested"
        to "کاملاً استراحت کردم",
    "Anything about the night?"
        to "چیزی درباره‌ی شب؟",
    "Note"
        to "یادداشت",
    "A night of under six hours or rated 1–2 counts as a bad night in the rest check."
        to "شبی کمتر از شش ساعت یا با امتیاز ۱ تا ۲ در بررسی استراحت «بد خوابیدم» حساب می‌شود.",
    "Hard to fall asleep"
        to "سخت خوابم برد",
    "Woke up often"
        to "زیاد بیدار شدم",
    "Woke too early"
        to "خیلی زود بیدار شدم",
    "Bad dreams"
        to "کابوس یا خواب ناخوشایند",
    "Vivid dreams"
        to "خواب‌های پررنگ",
    "Noise or light"
        to "سروصدا یا نور",
    "Too hot or cold"
        to "خیلی گرم یا سرد",
    "Caffeine late in the day"
        to "کافئین دیروقت",
    "Screen before bed"
        to "صفحه‌ی گوشی قبل از خواب",
    "Racing thoughts"
        to "ذهن شلوغ",
    "Pain or discomfort"
        to "درد یا ناراحتی جسمی",
    "Slept in"
        to "دیر بیدار شدم",
    "Napped during the day"
        to "در روز چرت زدم",
    "sleep · "
        to "خواب · ",
    "Sleep {}–{}"
        to "خواب {}–{}",
    "Sleep {}–{}, restful {}/5"
        to "خواب {}–{}، آرامش‌بخشی {}/۵",
    "All day: what was going on"
        to "کل روز: چه خبر بود",
    "All day: how you felt"
        to "کل روز: چه حسی داشتی",
    "Exam or deadline day"
        to "روز امتحان یا ددلاین",
    "Travel day"
        to "روز سفر",
    "Social day"
        to "روز اجتماعی و مهمانی",
    "Family matter"
        to "موضوع خانوادگی",
    "Holiday or day off"
        to "تعطیلی یا روز آزاد",
    "Anxious"
        to "مضطرب",
    "Upset or angry"
        to "ناراحت یا عصبانی",
    "Lonely"
        to "تنها",
    "Bad news"
        to "خبر بد",
    "Good news"
        to "خبر خوب",
    "Happy or excited"
        to "خوشحال یا هیجان‌زده",
    "Hungry"
        to "گرسنه",
    "On the phone or social media"
        to "سرگرم گوشی یا شبکه‌های اجتماعی",
    "Worried about something"
        to "نگران چیزی",
    "Frustrated"
        to "کلافه",
    "Bored"
        to "بی‌حوصله",
    "Restless"
        to "بی‌قرار",
    "Upsetting conversation"
        to "گفت‌وگوی ناراحت‌کننده",
    "Good conversation"
        to "گفت‌وگوی خوب",
    "Excited about something"
        to "هیجان‌زده برای چیزی",
    "Physically tired"
        to "خستگی جسمی",
    "Around you — rating kept out of the fit"
        to "اطرافت — امتیاز از برازش کنار گذاشته می‌شود",
    "How you felt — rating kept out of the fit"
        to "حس‌وحالت — امتیاز از برازش کنار گذاشته می‌شود",
    "No provider set up yet"
        to "هنوز ارائه‌دهنده‌ای تنظیم نشده",
    "In use"
        to "در حال استفاده",
    "no key"
        to "بدون کلید",
    "Providers"
        to "ارائه‌دهنده‌ها",
    "no model chosen"
        to "مدلی انتخاب نشده",
    "free"
        to "رایگان",
    "key saved"
        to "کلید ذخیره شده",
    "Edit"
        to "ویرایش",
    "Add a provider"
        to "افزودن ارائه‌دهنده",
    "Which provider?"
        to "کدام ارائه‌دهنده؟",
    "free tier"
        to "سطح رایگان",
    "Paid. The only one here that searches the web and lists its sources."
        to "پولی. تنها گزینه‌ای که در وب جستجو می‌کند و منابعش را می‌آورد.",
    "Has a free tier with a daily limit."
        to "سطح رایگان با سقف روزانه دارد.",
    "Many models behind one key; those whose name ends in :free cost nothing."
        to "مدل‌های زیادی با یک کلید؛ آن‌هایی که نامشان با ‎:free‎ تمام می‌شود رایگان‌اند.",
    "Has a free tier with rate limits; very fast."
        to "سطح رایگان با محدودیت سرعت دارد؛ خیلی سریع است.",
    "Paid: enter the prices of the model you pick."
        to "پولی: قیمت مدلی را که انتخاب می‌کنی وارد کن.",
    "Any service that speaks the OpenAI chat API: give its address."
        to "هر سرویسی که با API چت OpenAI کار می‌کند: آدرسش را بده.",
    "A free tier may use what you send to improve the provider's models. Each question sends only the snapshot it needs — dates, titles, the numbers you stated — never your log."
        to "سطح رایگان ممکن است از چیزی که می‌فرستی برای بهبود مدل‌هایش استفاده کند. هر سؤال فقط همان خلاصه‌ای را می‌فرستد که لازم دارد — تاریخ‌ها، عنوان‌ها، عددهایی که خودت گفته‌ای — و هرگز لاگت را.",
    "Address (base URL)"
        to "آدرس (base URL)",
    "The part before /chat/completions, e.g. https://api.example.com/v1"
        to "بخش قبل از ‎/chat/completions‎، مثلاً https://api.example.com/v1",
    "API key"
        to "کلید API",
    "Made at"
        to "ساخته می‌شود در",
    "A key is saved; leave this empty to keep it."
        to "یک کلید ذخیره شده؛ اگر خالی بگذاری همان می‌ماند.",
    "Stored encrypted with a key kept in the phone's Keystore."
        to "با کلیدی که در Keystore گوشی است رمزنگاری و ذخیره می‌شود.",
    "Type a model name, or fetch the list your key can use."
        to "نام مدل را بنویس، یا فهرست مدل‌هایی را که کلیدت اجازه می‌دهد بگیر.",
    "Fetch models"
        to "گرفتن فهرست مدل‌ها",
    "Pick a model"
        to "انتخاب مدل",
    "Input price per million tokens (\$)"
        to "قیمت ورودی برای هر میلیون توکن (دلار)",
    "0 on a free tier. If your key is billed, use the provider's price for this model."
        to "در سطح رایگان ۰. اگر کلیدت هزینه دارد، قیمت ارائه‌دهنده برای این مدل را بگذار.",
    "Output price per million tokens (\$)"
        to "قیمت خروجی برای هر میلیون توکن (دلار)",
    "Remove"
        to "حذف",
    "Saved. The key is encrypted on this phone."
        to "ذخیره شد. کلید روی همین گوشی رمزنگاری شده است.",
    "Removed."
        to "حذف شد.",
    "The provider listed no models."
        to "ارائه‌دهنده هیچ مدلی فهرست نکرد.",
    "Set up a provider in the LLM panel first."
        to "اول در بخش LLM یک ارائه‌دهنده تنظیم کن.",
    "Add the API key for {} in the LLM panel first."
        to "اول کلید API مربوط به {} را در بخش LLM وارد کن.",
    "Enter the API key first: the list depends on it."
        to "اول کلید API را وارد کن: فهرست به آن بستگی دارد.",
    "This day's plan was not kept; its blocks were rebuilt from what you logged."
        to "برنامه‌ی این روز نگه داشته نشده بود؛ بلوک‌هایش از روی چیزهایی که ثبت کرده بودی بازسازی شد.",

    // ------------------------------------------------------- fixes
    "{} has answered {} questions today, the most this app allows per provider. Pick another provider, or raise \"Calls per day at most\" in the LLM settings."
        to "{} امروز به {} سؤال جواب داده که سقفِ خودِ اپ برای هر ارائه‌دهنده است. ارائه‌دهنده‌ی دیگری انتخاب کن، یا در تنظیمات LLM «حداکثر فراخوان در روز» را بیشتر کن.",
    "{} did not answer: {}"
        to "{} جواب نداد: {}",
    "To save, set"
        to "برای ذخیره، این‌ها را مشخص کن",
    "when you went to bed"
        to "زمان خوابیدن",
    "when you got up"
        to "زمان بیدار شدن",
    "Filled in, not saved yet"
        to "پر شده، هنوز ذخیره نشده",
    "Save the night"
        to "ذخیره‌ی خواب",
    "Discard changes"
        to "دور انداختن تغییرات",

    // --------------------------------------------------- day match
    "How the day matched the plan"
        to "روز چقدر با برنامه جور بود",
    "Fill in the boxes above — how long each block took, when it started, how drained you were — and rate a few hours of energy. The match is drawn here as you go."
        to "کادرهای بالا را پر کن — هر بلوک چقدر طول کشید، کی شروع شد، چقدر خسته شدی — و انرژی چند ساعت را امتیاز بده. میزان انطباق همین‌جا همان‌طور که پیش می‌روی کشیده می‌شود.",
    "{0} blocks not reported yet"
        to "{0} بلوک هنوز گزارش نشده",
    "Duration"
        to "مدت",
    "Start time"
        to "زمان شروع",
    "Energy"
        to "انرژی",
    "{0} blocks reported · {1} skipped · {2} not reported"
        to "{0} بلوک گزارش‌شده · {1} انجام‌نشده · {2} بی‌گزارش",
    "Duration: minutes done against planned. Start: an hour off counts as nothing. Energy and fatigue: what the plan expected of you against what you reported."
        to "مدت: دقیقه‌های انجام‌شده در برابر برنامه. شروع: یک ساعت اختلاف یعنی صفر. انرژی و خستگی: آنچه برنامه از تو انتظار داشت در برابر آنچه گزارش دادی.",
    "Energy: expected and reported"
        to "انرژی: پیش‌بینی‌شده و گزارش‌شده",
    "expected"
        to "پیش‌بینی",
    "you said"
        to "گزارش تو",
    "Hide the blocks"
        to "پنهان کردن بلوک‌ها",
    "Block by block"
        to "بلوک به بلوک",
    "match"
        to "انطباق",
    "not reported"
        to "گزارش نشده",
    "skipped"
        to "انجام نشد",

    // ------------------------------------------------- setup board
    "Goals and activities"
        to "اهداف و فعالیت‌ها",
    "Your goals with the work that serves them, then upkeep and commitments. Tap anything to open it; a red mark means something in it is still missing."
        to "اهدافت با کارهایی که به آن‌ها خدمت می‌کنند، بعد نگهداری و تعهدها. روی هر چیزی بزن تا باز شود؛ علامت قرمز یعنی هنوز چیزی در آن کم است.",
    "Cards"
        to "کارت‌ها",
    "Week"
        to "هفته",
    "Goal work that serves no goal"
        to "کار هدف‌داری که به هیچ هدفی وصل نیست",
    "Meals, prayer, exercise, chores: no progress, but they keep you able to work."
        to "غذا، نماز، ورزش، کار خانه: پیشرفتی نمی‌آورند، ولی توان کارت را نگه می‌دارند.",
    "Classes and anything else whose time is not yours to choose, and the travel around them."
        to "کلاس‌ها و هر چیزی که زمانش دست تو نیست، و رفت‌وآمد دورشان.",
    "Activity"
        to "فعالیت",
    "Commitment"
        to "تعهد",
    "Unnamed goal"
        to "هدف بی‌نام",
    "days left"
        to "روز مانده",
    "h per week"
        to "ساعت در هفته",
    "Weight"
        to "وزن",
    "Your waking hours, Saturday to Friday. Solid: fixed times. Bands: where an activity is allowed. Tap one to open it."
        to "ساعت‌های بیداری‌ات، شنبه تا جمعه. پررنگ: زمان‌های ثابت. نوارها: جایی که یک فعالیت مجاز است. روی هرکدام بزن تا باز شود.",
    "Classes and fixed times"
        to "کلاس‌ها و زمان‌های ثابت",
    "Step"
        to "مرحله",
    "Basics"
        to "پایه",
    "When"
        to "زمان",
    "What"
        to "چه",
    "How much"
        to "چقدر",
    "Times"
        to "زمان‌ها",
    "Tap again to remove"
        to "برای حذف دوباره بزن",
    "What kind of goal?"
        to "چه نوع هدفی؟",
    "With a deadline"
        to "با ددلاین",
    "Never finishes; paced by hours a week (a language, coursework)."
        to "تمام نمی‌شود؛ با ساعت در هفته پیش می‌رود (یک زبان، درس‌ها).",
    "A set amount of work by a date (30 videos by 12 Oct)."
        to "مقدار مشخصی کار تا یک تاریخ (۳۰ ویدیو تا ۲۰ مهر).",
    "Ceiling per day"
        to "سقف روزانه",
    "set in Priorities, never typed here."
        to "در اولویت‌ها تعیین می‌شود، نه این‌جا.",
    "For an ongoing goal one unit is one hour of work, so this is usually 1."
        to "در هدف همیشگی هر واحد یک ساعت کار است، پس معمولاً ۱ است.",
    "New goal work"
        to "کار هدف‌دار جدید",
    "What is it?"
        to "چه کاری است؟",
    "Deep study"
        to "مطالعه‌ی عمیق",
    "Lectures or videos"
        to "کلاس یا ویدیو",
    "Language practice"
        to "تمرین زبان",
    "Coding or a project"
        to "برنامه‌نویسی یا پروژه",
    "Writing"
        to "نوشتن",
    "Planning and admin"
        to "برنامه‌ریزی و کارهای اداری",
    "Meal"
        to "غذا",
    "Prayer"
        to "نماز",
    "Exercise"
        to "ورزش",
    "Chores"
        to "کار خانه",
    "Walk or break"
        to "پیاده‌روی یا استراحت",
    "Study"
        to "مطالعه",
    "Lectures"
        to "کلاس‌ها",
    "Project"
        to "پروژه",
    "Planning"
        to "برنامه‌ریزی",
    "Walk"
        to "پیاده‌روی",
    "Start blank"
        to "شروع از صفر",
    "A kind fills in starting values — hours, block length, how draining it is. Every one of them is shown on the next steps for you to check; nothing is saved until you press Save."
        to "نوع، مقدارهای شروع را پر می‌کند — ساعت‌ها، طول بلوک، چقدر خسته‌کننده است. همه‌شان در مرحله‌های بعد نشان داده می‌شوند تا بررسی‌شان کنی؛ تا Save را نزنی چیزی ذخیره نمی‌شود.",
    "Add a goal first; goal work has to serve one."
        to "اول یک هدف اضافه کن؛ کار هدف‌دار باید به یک هدف خدمت کند.",
    "Block length"
        to "طول بلوک",
    "Shortest: below this a session is not worth starting. Longest: past this, attention fades faster than work gets done."
        to "کوتاه‌ترین: کمتر از این، شروع کردن نمی‌ارزد. بلندترین: بیشتر از این، تمرکز زودتر از پیشرفت کار تحلیل می‌رود.",
    "Fatigue load"
        to "بار خستگی",
    "costs nothing"
        to "هزینه‌ای ندارد",
    "most draining"
        to "خسته‌کننده‌ترین",
    "Per hour: how much it tires you."
        to "در هر ساعت: چقدر خسته‌ات می‌کند.",
    "Recovery"
        to "بازیابی",
    "restores nothing"
        to "چیزی برنمی‌گرداند",
    "fully restoring"
        to "کاملاً جان می‌دهد",
    "Per hour: how much it gives back. Meals and walks belong here."
        to "در هر ساعت: چقدر انرژی برمی‌گرداند. غذا و پیاده‌روی این‌جا هستند.",
    "Changeover before it"
        to "زمان آماده شدن قبلش",
    "Time to get into it: changing for the gym, walking to the kitchen."
        to "زمان لازم برای شروعش: لباس عوض کردن برای ورزش، رفتن به آشپزخانه.",
    "min"
        to "دقیقه",
    "Class or fixed session"
        to "کلاس یا جلسه‌ی ثابت",
    "A trip that wraps around whatever happens out of the house that day. Its starting values are shown on the next step."
        to "سفری که دور هر چیزی که آن روز بیرون از خانه است می‌پیچد. مقدارهای شروعش در مرحله‌ی بعد نشان داده می‌شوند.",
    "Times that are not yours to choose: a class, a lab, work hours."
        to "زمان‌هایی که انتخابشان دست تو نیست: کلاس، آزمایشگاه، ساعت کاری.",
    "Typical one-way time"
        to "زمان معمول یک‌طرفه",
    "reliable"
        to "قابل‌اعتماد",
    "unpredictable"
        to "غیرقابل‌پیش‌بینی",
    "often late"
        to "اغلب دیر",
    "rarely late"
        to "به‌ندرت دیر",
    "Each session has its own load and recovery: a four-hour lab drains more than a lecture."
        to "هر جلسه بار و بازیابی خودش را دارد: یک آزمایشگاه چهارساعته بیشتر از یک کلاس خسته می‌کند.",
    "The trip wraps around everything marked out of the house that day. It is never dropped — if it will not fit, the day plan reports the conflict instead."
        to "سفر دور همه‌ی چیزهایی که آن روز بیرون از خانه‌اند می‌پیچد. هرگز حذف نمی‌شود — اگر جا نشود، برنامه‌ی روز تداخل را گزارش می‌دهد.",

    // ---------------------------------------- navigator stages 2-4
    "This week's hours"
        to "ساعت‌های این هفته",
    "No week planned yet. The Navigator plans each coming week on Thursday night; you can plan one now."
        to "هنوز هفته‌ای برنامه‌ریزی نشده. ناوبر هر پنج‌شنبه شب هفته‌ی بعد را برنامه‌ریزی می‌کند؛ همین حالا هم می‌توانی.",
    "Plan the coming week"
        to "برنامه‌ریزی هفته‌ی پیش رو",
    "Plan the week again"
        to "برنامه‌ریزی دوباره‌ی هفته",
    "Expected goal hours"
        to "ساعت هدف‌دار مورد انتظار",
    "left free"
        to "آزاد مانده",
    "The sprint's own plan is more than the week is expected to hold, by"
        to "برنامه‌ی خود اسپرینت از ظرفیت مورد انتظار هفته بیشتر است، به اندازه‌ی",
    "Accepted — Chronos's weekly floors follow it."
        to "پذیرفته شد — کف‌های هفتگی Chronos از آن پیروی می‌کنند.",
    "Waiting for you to accept it."
        to "منتظر پذیرش توست.",
    "No Chronos activity does this node's work, so its hours cannot reach your days. Link one:"
        to "هیچ فعالیتی در Chronos کار این گره را انجام نمی‌دهد، پس ساعت‌هایش به روزهایت نمی‌رسد. یکی را وصل کن:",
    "Chronos activity"
        to "فعالیت Chronos",
    "Or add goal work for it in Setup (a goal and an activity), then link it here."
        to "یا در تنظیمات برایش کار هدف‌دار بساز (یک هدف و یک فعالیت)، بعد این‌جا وصلش کن.",
    "Accept"
        to "پذیرش",
    "Apply again"
        to "اعمال دوباره",
    "Change hours"
        to "تغییر ساعت‌ها",
    "Done editing"
        to "پایان ویرایش",
    "Accepting sets the weekly floor of each ongoing Chronos goal to its nodes' hours (a goal whose nodes get nothing this week waits), rebuilds the plan and sends it to your calendar. Goals with a deadline keep their own pace."
        to "با پذیرش، کف هفتگی هر هدف همیشگی Chronos برابر ساعت‌های گره‌هایش می‌شود (هدفی که این هفته چیزی نمی‌گیرد منتظر می‌ماند)، برنامه دوباره ساخته و به تقویمت فرستاده می‌شود. هدف‌های ددلاین‌دار سرعت خودشان را دارند.",
    "What changed in Chronos"
        to "چه چیزی در Chronos تغییر کرد",
    "weekly floor"
        to "کف هفتگی",
    "daily ceiling"
        to "سقف روزانه",
    "Stop following the Navigator"
        to "دیگر از ناوبر پیروی نکن",
    "Applied: Chronos's weekly floors follow this week, and the plan was rebuilt."
        to "اعمال شد: کف‌های هفتگی Chronos از این هفته پیروی می‌کنند و برنامه دوباره ساخته شد.",
    "Your own weekly floors and ceilings are back."
        to "کف‌ها و سقف‌های خودت برگشتند.",
    "The coming week is planned. Check it and accept."
        to "هفته‌ی پیش رو برنامه‌ریزی شد. بررسی‌اش کن و بپذیر.",
    "Some answers are still missing in the Navigator tab."
        to "هنوز چند پاسخ در تب ناوبری کم است.",
    "No goal graph yet."
        to "هنوز گراف اهدافی نیست.",
    "Forecast"
        to "پیش‌بینی",
    "Not run yet. It runs every night by itself, or now:"
        to "هنوز اجرا نشده. هر شب خودش اجرا می‌شود، یا همین حالا:",
    "Forecast now"
        to "پیش‌بینی کن",
    "Forecast again"
        to "پیش‌بینی دوباره",
    "Run"
        to "اجرا",
    "simulated futures"
        to "آینده‌ی شبیه‌سازی‌شده",
    "For each deadline: in how many of the simulated futures it is met, the likely finishing date, and the change since a week ago."
        to "برای هر مهلت: در چند درصد از آینده‌های شبیه‌سازی‌شده به آن می‌رسی، تاریخ محتمل پایان، و تغییرش نسبت به یک هفته پیش.",
    "likely"
        to "محتمل",
    "not finished in most futures"
        to "در بیشتر آینده‌ها تمام نمی‌شود",
    "Finishing date"
        to "تاریخ پایان",
    "Critical in"
        to "بحرانی در",
    "of the futures: being late here cost a deadline further on."
        to "از آینده‌ها: دیر شدن این‌جا مهلتی جلوتر را از دست داد.",
    "Show fewer"
        to "نمایش کمتر",
    "What your log has taught"
        to "آنچه لاگت یاد داده",
    "You do"
        to "تو",
    "of planned goal sessions"
        to "از جلسه‌های هدف‌دار برنامه‌ریزی‌شده را انجام می‌دهی",
    "done"
        to "انجام‌شده",
    "takes"
        to "طول می‌کشد",
    "of what is planned"
        to "برابرِ برنامه",
    "sessions"
        to "جلسه",
    "Ideal day"
        to "روز ایده‌آل",
    "Busy day"
        to "روز شلوغ",
    "Stressful day"
        to "روز پرتنش",
    "goal hours"
        to "ساعت هدف‌دار",
    "days ticked"
        to "روز تیک‌خورده",
    "Sharpest hour so far"
        to "سرحال‌ترین ساعت تا حالا",
    "Your priors stay as you gave them; these are recomputed from them and the whole log every night."
        to "پیشین‌هایت همان‌طور که دادی می‌مانند؛ این‌ها هر شب از روی آن‌ها و کل لاگ از نو حساب می‌شوند.",
    "What kind of day was it?"
        to "امروز چه جور روزی بود؟",
    "Ideal"
        to "ایده‌آل",
    "Busy"
        to "شلوغ",
    "Stressful"
        to "پرتنش",
    "Suggested from your check-in and today's classes; tap to confirm or change."
        to "از روی چک‌این و کلاس‌های امروز پیشنهاد شده؛ برای تأیید یا تغییر بزن.",
    "Unusual day — do not learn from it"
        to "روز غیرعادی — از آن یاد نگیر",

    // ---------------------------------------------- navigator stages 5 and 6
    "Fill in every hour you emptied (0 gives the node nothing this week)."
        to "هر ساعتی را که پاک کردی دوباره پر کن (۰ یعنی این گره این هفته ساعتی نمی‌گیرد).",
    "Horizon and Day open once Setup is complete."
        to "افق و روز بعد از کامل شدن تنظیمات باز می‌شوند.",
    "Ask the LLM for ways to recover"
        to "از LLM راه‌های جبران را بپرس",
    "For"
        to "برای",
    "The same question goes out by itself at night when a deadline falls below 60%."
        to "وقتی احتمال یک مهلت زیر ۶۰٪ برود، همین سؤال شب‌ها خودکار فرستاده می‌شود.",
    "Options: approve the ones you want; each was forecast by the app to measure its effect."
        to "گزینه‌ها: هرکدام را که می‌خواهی تأیید کن؛ اثر هرکدام را خود اپ با پیش‌بینی اندازه گرفته است.",
    "A deadline became unlikely"
        to "یک مهلت کم‌احتمال شد",
    "A deadline became much likelier"
        to "یک مهلت خیلی محتمل‌تر شد",
    "Time to break a node down"
        to "وقت شکستن یک گره رسیده",
    "A gate's window changed"
        to "پنجره‌ی یک دروازه تغییر کرد",
    "You changed your goals"
        to "هدف‌هایت را تغییر دادی",
    "Monthly review"
        to "مرور ماهانه",
    "Breakdown you asked for"
        to "شکستنی که خواستی",
    "Ways to recover you asked for"
        to "راه‌های جبرانی که خواستی",
    "Estimate you asked for"
        to "تخمینی که خواستی",
    "Measured by the app's own forecast, with and without this, on the same simulated futures."
        to "با پیش‌بینی خود اپ اندازه گرفته شده: با این تغییر و بدون آن، روی همان آینده‌های شبیه‌سازی‌شده.",
    "Set up the new tasks in Chronos like"
        to "کارهای جدید در Chronos مثل این فعالیت تنظیم شوند",
    "Each task becomes its own goal and activity, with this activity's windows, block lengths and load. It gets hours only from a week you accept."
        to "هر کار یک هدف و فعالیت جدا می‌شود، با پنجره‌ها، طول بلوک‌ها و بار همین فعالیت. فقط از هفته‌ای که بپذیری ساعت می‌گیرد.",
    "from"
        to "از",
    "fine"
        to "ریز",
    "medium"
        to "متوسط",
    "activity"
        to "فعالیت",
    "Done when"
        to "تمام است وقتی",
    "After"
        to "بعد از",
    "To raise"
        to "برای مطرح کردن",
    "At least"
        to "حداقل",
    "h a week on"
        to "ساعت در هفته برای",
    "Pause"
        to "توقف",
    "New deadline for"
        to "مهلت جدید برای",
    "Automatic checks"
        to "بررسی‌های خودکار",
    "Every night after the forecast the Navigator checks six triggers. When one fires, the LLM is asked on its own — within your budget and daily limit — and its answer waits in the proposals inbox."
        to "ناوبر هر شب بعد از پیش‌بینی شش تریگر را بررسی می‌کند. وقتی یکی فعال شود، خودش از LLM می‌پرسد — در حد بودجه و سقف روزانه‌ات — و جوابش در صندوق پیشنهادها منتظر تو می‌ماند.",
    "Ask the LLM automatically"
        to "خودکار از LLM بپرس",
    "Waiting to be sent"
        to "در صف فرستادن",
    "Check now"
        to "همین حالا بررسی کن",
    "What they are"
        to "این‌ها چه هستند",
    "A deadline's chance falls below 60%: ways to recover it. It does not fire again for that deadline until the chance has been back above 70%."
        to "احتمال یک مهلت زیر ۶۰٪ برود: راه‌های جبرانش. برای همان مهلت دوباره فعال نمی‌شود تا احتمالش یک بار به بالای ۷۰٪ برگردد.",
    "A deadline's chance rises by more than 15 points within 7 days: is there room to pull other work forward?"
        to "احتمال یک مهلت در ۷ روز بیش از ۱۵ واحد بالا برود: آیا جا هست کارهای دیگر را جلو بیندازی؟",
    "A coarse node reaches its breakdown date: it is broken into smaller tasks."
        to "یک گره‌ی درشت به تاریخ شکستنش برسد: به کارهای کوچک‌تر شکسته می‌شود.",
    "A gate's window opens or its deadline changes: its odds are looked at again, with a web search when the provider can."
        to "پنجره‌ی یک دروازه باز شود یا مهلتش عوض شود: شانسش دوباره بررسی می‌شود، اگر ارائه‌دهنده بتواند با جستجوی وب.",
    "You changed goals, deadlines, commitments or answers: the graph is checked for what the change broke."
        to "هدف‌ها، مهلت‌ها، تعهدها یا پاسخ‌هایت را عوض کنی: گراف بررسی می‌شود که این تغییر چه چیزی را به هم زده.",
    "The first Wednesday of each month: a review of the whole picture, and the questions worth asking you."
        to "اولین چهارشنبه‌ی هر ماه: مرور کل تصویر، و سؤال‌هایی که ارزش پرسیدن از تو را دارند.",
    "Not fired yet"
        to "هنوز فعال نشده",
    "Last fired"
        to "آخرین بار فعال شد",
    "Answers that would help most"
        to "پاسخ‌هایی که بیشترین کمک را می‌کنند",
    "The numbers you gave that the forecast is most sensitive to: each is tried at the low and the high end of what is believed now, and the deadlines' weighted odds compared. A better answer to the top ones sharpens the forecast most."
        to "عددهایی که داده‌ای و پیش‌بینی بیش از همه به آن‌ها حساس است: هرکدام یک بار در سر پایین و یک بار در سر بالای باور فعلی امتحان می‌شود و احتمال وزن‌دار مهلت‌ها مقایسه می‌شود. پاسخ دقیق‌تر به بالایی‌ها پیش‌بینی را بیشتر از همه دقیق می‌کند.",
    "Not ranked yet. It is ranked at each monthly review, or now (it may take a minute):"
        to "هنوز رتبه‌بندی نشده. در هر مرور ماهانه رتبه‌بندی می‌شود، یا همین حالا (ممکن است یک دقیقه طول بکشد):",
    "Nothing you gave moves the forecast much."
        to "هیچ‌کدام از عددهایی که داده‌ای پیش‌بینی را زیاد جابه‌جا نمی‌کند.",
    "Ranked"
        to "رتبه‌بندی شده در",
    "Rank them now"
        to "همین حالا رتبه‌بندی کن",
    "Rank again"
        to "دوباره رتبه‌بندی کن",
    "Could move the weighted odds of your deadlines by"
        to "می‌تواند احتمال وزن‌دار مهلت‌هایت را این‌قدر جابه‌جا کند:",
    "How many hours will this take?"
        to "این کار چند ساعت طول می‌کشد؟",
    "How likely is this?"
        to "این چقدر محتمل است؟",
    "What will days be like in this stretch?"
        to "روزهای این بازه چطور خواهند بود؟",
    "now"
        to "الان",
    "now about"
        to "الان حدود",
    "Used when you ask, and when a trigger fires (see Automatic checks). It never sees your log, and what it says is a proposal you approve or reject."
        to "وقتی بپرسی و وقتی یک تریگر فعال شود به کار می‌رود (بررسی‌های خودکار را ببین). هیچ‌وقت لاگت را نمی‌بیند و هرچه بگوید پیشنهادی است که تأیید یا رد می‌کنی.",
    "A task of"
        to "یکی از کارهای",
    "Broken down: its tasks carry the work, and it is reached when they are done."
        to "شکسته شده: کارهایش بار کار را می‌برند و وقتی همه تمام شوند این هم تمام است.",
    "Open again"
        to "دوباره باز کن",
    "Mark done"
        to "تمام شد",
    "Break it down now"
        to "همین حالا بشکنش",
    "No trigger fired."
        to "هیچ تریگری فعال نشد.",
    "Fired: {}. Their answers arrive in the proposals inbox."
        to "فعال شد: {}. جواب‌هایشان در صندوق پیشنهادها می‌رسد.",
    "Marked done."
        to "تمام‌شده علامت خورد.",
    "Open again."
        to "دوباره باز شد.",
    "Noted."
        to "دیده شد.",
    "Approved. The coming week was planned again with it: check it and accept."
        to "تأیید شد. هفته‌ی آینده با آن دوباره برنامه‌ریزی شد: بررسی‌اش کن و بپذیر.",
    "Approved: {0} tasks are now goals and activities in Chronos. The coming week was planned again with them — accept it to send their hours to your days."
        to "تأیید شد: {0} کار حالا هدف و فعالیت در Chronos هستند. هفته‌ی آینده با آن‌ها دوباره برنامه‌ریزی شد — بپذیرش تا ساعت‌هایشان به روزهایت برسد.",
    "{0} proposals are waiting in the inbox."
        to "{0} پیشنهاد در صندوق منتظر است.",
    "Pick the Chronos activity the new tasks are set up like."
        to "فعالیتی از Chronos را انتخاب کن که کارهای جدید مثل آن تنظیم شوند.",
    "That proposal is gone."
        to "آن پیشنهاد دیگر وجود ندارد.",

    // ---------------------------------------------- navigator stages 5 and 6
    "Chronos ⇄ Navigator"
        to "Chronos ⇄ ناوبری",
    "Where a node and the Chronos goal it is worked through disagree. Nothing changes on either side until you choose which one is right — or keep both as they are."
        to "جاهایی که یک گره با هدفی در Chronos که از طریق آن کار می‌شود اختلاف دارد. تا انتخاب نکنی کدام درست است — یا هر دو را همان‌طور نگه داری — در هیچ طرف چیزی عوض نمی‌شود.",
    "Different deadlines"
        to "مهلت‌ها فرق دارند",
    "Different number of units"
        to "تعداد واحدها فرق دارد",
    "Hours per unit: the log disagrees with Chronos"
        to "ساعت هر واحد: لاگ با Chronos هم‌نظر نیست",
    "Finished in Chronos, open in the graph"
        to "در Chronos تمام شده، در گراف باز است",
    "Done in the graph, units left in Chronos"
        to "در گراف تمام شده، در Chronos واحد مانده",
    "Learned from your log"
        to "یادگرفته از لاگت",
    "Use Chronos's in the graph"
        to "مقدار Chronos را در گراف بگذار",
    "Use it in Chronos"
        to "در Chronos اعمالش کن",
    "Use the graph's in Chronos"
        to "مقدار گراف را در Chronos بگذار",
    "Keep both"
        to "هر دو بمانند",
    "open"
        to "باز",
    "finished"
        to "تمام‌شده",
    "Your advisor set this deadline: move it in the graph only after talking to them."
        to "این مهلت را استادت گذاشته: فقط بعد از صحبت با او در گراف جابه‌جایش کن.",
    "kept"
        to "انجام‌شده",
    "level"
        to "سطح",
    "this week"
        to "این هفته",
    "unit"
        to "واحد",
    "Now"
        to "الان",
    "Chance by the deadline"
        to "احتمال تا مهلت",
    "a week ago"
        to "یک هفته پیش",
    "Likely finish"
        to "پایان محتمل",
    "of the futures"
        to "از آینده‌ها",
    "Deadline in the graph"
        to "مهلت در گراف",
    "in Chronos"
        to "در Chronos",
    "from Chronos"
        to "از Chronos",
    "Learned speed"
        to "سرعت یادگرفته‌شده",
    "per"
        to "برای هر",
    "Takes"
        to "طول می‌کشد",
    "of the time planned"
        to "زمانِ برنامه‌ریزی‌شده",
    "Share of planned time worked"
        to "سهم زمان برنامه‌ریزی‌شده که کار شد",
    "Hours logged"
        to "ساعت ثبت‌شده",
    "Hours this week"
        to "ساعت این هفته",
    "getting faster"
        to "سریع‌تر می‌شود",
    "getting slower"
        to "کندتر می‌شود",
    "a week"
        to "در هفته",
    "You work"
        to "تو",
    "of the goal time you plan"
        to "از زمان هدف‌داری را که برنامه‌ریزی می‌کنی کار می‌کنی",
    "planned days, recent ones counting most"
        to "روز برنامه‌ریزی‌شده، روزهای اخیر با وزن بیشتر",
    "of its planned time worked"
        to "از زمان برنامه‌ریزی‌شده‌اش کار شد",
    "Your priors stay as you gave them; these are recomputed from them and the whole log every night. Speed is followed by a Kalman filter that also tracks its trend, so recent days count most."
        to "پیشین‌هایت همان‌طور که دادی می‌مانند؛ این‌ها هر شب از روی آن‌ها و کل لاگ از نو حساب می‌شوند. سرعت با یک فیلتر کالمن دنبال می‌شود که روندش را هم می‌گیرد، پس روزهای اخیر بیشترین وزن را دارند.",
    "Chronos plans"
        to "Chronos برنامه می‌ریزد",
    "your log says"
        to "لاگت می‌گوید",
    "With the learned value Chronos plans enough time to meet the deadline."
        to "با مقدار یادگرفته، Chronos برای رسیدن به مهلت وقت کافی برنامه‌ریزی می‌کند.",

    // ---------------------------------------------- navigator stages 5 and 6
    "Reasons"
        to "دلیل‌ها",
    "Map of the whole path"
        to "نقشه‌ی کل مسیر",
    "Each node sits at its likely finishing date (time is compressed: the coming weeks wide, the years ahead narrow), in the lane of its phase. Colour: chance of meeting its deadline. Thicker red lines: the chain that most often made a deadline late. Pinch to zoom, drag to move, tap a node."
        to "هر گره در تاریخ پایان محتملش و در ردیف فاز خودش نشسته (زمان فشرده است: هفته‌های پیش رو باز، سال‌های بعد فشرده). رنگ: احتمال رسیدن به مهلت. خط‌های قرمز پررنگ‌تر: زنجیره‌ای که بیشتر از همه مهلتی را عقب انداخته. با دو انگشت بزرگ و کوچک کن، با کشیدن جابه‌جا کن و روی گره بزن.",
    "70% or more"
        to "۷۰٪ یا بیشتر",
    "40–70%"
        to "۴۰ تا ۷۰٪",
    "under 40%"
        to "زیر ۴۰٪",
    "no deadline"
        to "بدون مهلت",
    "Dashed red tail: from the deadline (◇) to a likely finish after it. Dashed line: one of several ways in, or only helps."
        to "دنباله‌ی قرمز خط‌چین: از مهلت (◇) تا پایان محتملی که بعد از آن است. خط‌چین خاکستری: یکی از چند راه ورود، یا فقط کمک‌کننده.",
    "Tap a node to see what leads to it."
        to "روی یک گره بزن تا ببینی چه چیزهایی به آن می‌رسند.",
    "The path to"
        to "مسیر رسیدن به",
    "It leads to"
        to "به این‌ها می‌رسد",
    "needs all"
        to "همه لازم‌اند",
    "needs"
        to "لازم دارد",
    "one of the ways {0}"
        to "راه {0}",
    "one way"
        to "یک راه",

    // ---------------------------------------------- navigator stages 5 and 6
    "later"
        to "بعدتر",
    "rest of {0}"
        to "بقیه‌ی {0}",
    "Columns are stretches of time — months while they are near, then quarters, then years — and rows are the phases. Each node sits where it will likely finish; colour is its chance of meeting its deadline. Tap a node: the chain that decides its date turns red. Drag sideways to move, pinch to zoom."
        to "ستون‌ها بازه‌های زمانی‌اند — ماه‌ها تا وقتی نزدیک‌اند، بعد فصل‌ها، بعد سال‌ها — و ردیف‌ها فازها. هر گره جایی نشسته که احتمالاً تمام می‌شود؛ رنگ احتمال رسیدن به مهلت است. روی گره بزن تا زنجیره‌ای که تاریخش را تعیین می‌کند قرمز شود. برای جابه‌جایی به پهلو بکش، برای بزرگ‌نمایی با دو انگشت باز کن.",
    "Jan"
        to "ژانویه",
    "Feb"
        to "فوریه",
    "Mar"
        to "مارس",
    "Apr"
        to "آوریل",
    "May"
        to "مه",
    "Jun"
        to "ژوئن",
    "Jul"
        to "ژوئیه",
    "Aug"
        to "اوت",
    "Sep"
        to "سپتامبر",
    "Oct"
        to "اکتبر",
    "Nov"
        to "نوامبر",
    "Dec"
        to "دسامبر",

    // ---------------------------------------------- navigator stages 5 and 6
    "How heavy did today feel for your mind?"
        to "امروز برای ذهنت چقدر سنگین بود؟",
    "And for your body?"
        to "و برای بدنت؟",
    "Mentally tired afterwards"
        to "بعدش چقدر از نظر ذهنی خسته بودی",
    "Physically tired afterwards"
        to "بعدش چقدر از نظر جسمی خسته بودی",
    "teaches this activity's mental load"
        to "بار ذهنی این فعالیت را یاد می‌دهد",
    "teaches this activity's physical load"
        to "بار جسمی این فعالیت را یاد می‌دهد",
    "Mentally tired on arrival"
        to "موقع رسیدن چقدر ذهنی خسته بودی",
    "Physically tired on arrival"
        to "موقع رسیدن چقدر جسمی خسته بودی",
    "teaches this trip's mental load"
        to "بار ذهنی این رفت‌وآمد را یاد می‌دهد",
    "teaches this trip's physical load"
        to "بار جسمی این رفت‌وآمد را یاد می‌دهد",
    "mind {0}/5"
        to "ذهن {0}/5",
    "body {0}/5"
        to "بدن {0}/5",
    "mind"
        to "ذهن",
    "body"
        to "بدن",
    "The split between mental and physical below was suggested by the app."
        to "تقسیم ذهنی و جسمی پایین را اپ پیشنهاد داده است.",
    "Keep these values"
        to "همین مقدارها بمانند",
    "Mental load"
        to "بار ذهنی",
    "Physical load"
        to "بار جسمی",
    "Mental recovery"
        to "بازیابی ذهنی",
    "Physical recovery"
        to "بازیابی جسمی",
    "Per hour: how much it tires your mind."
        to "در هر ساعت: چقدر ذهنت را خسته می‌کند.",
    "Per hour: how much it tires your body. A workout is high here and low above."
        to "در هر ساعت: چقدر بدنت را خسته می‌کند. ورزش اینجا بالاست و بالایی پایین.",
    "Per hour: how much it rests your mind. Meals, walks and exercise belong here."
        to "در هر ساعت: چقدر ذهنت را استراحت می‌دهد. غذا، پیاده‌روی و ورزش اینجا هستند.",
    "Per hour: how much it rests your body. Meals and naps belong here."
        to "در هر ساعت: چقدر بدنت را استراحت می‌دهد. غذا و چرت اینجا هستند.",
    "How much a tired body costs the mind"
        to "بدن خسته چقدر از ذهن کم می‌کند",
    "How far physical fatigue, once past the line below, lowers what you can do with your head."
        to "خستگی جسمی وقتی از خط پایین بگذرد، چقدر از توان کار ذهنی‌ات کم می‌کند.",
    "Physical fatigue line"
        to "خط خستگی جسمی",
    "Below it a tired body does not slow your thinking; above it, it does (0 to 1). Your log refines it."
        to "زیر آن بدن خسته فکرت را کند نمی‌کند؛ بالای آن می‌کند (۰ تا ۱). لاگت آن را دقیق‌تر می‌کند.",
    "Physical recovery per hour"
        to "بازیابی جسمی در هر ساعت",
    "How much a nap rests your body."
        to "چرت چقدر بدنت را استراحت می‌دهد.",
    "Safe physical fatigue line"
        to "خط امن خستگی جسمی",
    "Like the line above, for the body: below it physical fatigue is free."
        to "مثل خط بالایی، برای بدن: زیر آن خستگی جسمی هزینه‌ای ندارد.",
    "Physical recovery while resting"
        to "بازیابی جسمی در حال استراحت",
    "How fast doing nothing rests your body."
        to "کاری نکردن با چه سرعتی بدنت را استراحت می‌دهد.",
    "How much a tired mind makes effort cost"
        to "ذهن خسته چقدر تلاش جسمی را گران‌تر می‌کند",
    "0.3 means that with your mind fully tired, physical effort tires you 30% more."
        to "۰٫۳ یعنی وقتی ذهنت کاملاً خسته است، تلاش جسمی ۳۰٪ بیشتر خسته‌ات می‌کند.",
    "Mental and physical fatigue"
        to "خستگی ذهنی و جسمی",
    "Fatigue now has two kinds. For each activity the app suggested how much it tires (and rests) your mind and your body, from what it is. Until you confirm, the planner uses the suggestion. Tap one to change it."
        to "خستگی حالا دو نوع دارد. اپ برای هر فعالیت، از روی اینکه چیست، پیشنهاد داده چقدر ذهن و بدنت را خسته می‌کند (و استراحت می‌دهد). تا تأیید نکنی، زمان‌بند با همین پیشنهاد کار می‌کند. برای تغییر روی هرکدام بزن.",
    "load / recovery per hour"
        to "بار / بازیابی در هر ساعت",
    "Keep all as suggested"
        to "همه همان پیشنهاد بمانند",
    "Mental fatigue"
        to "خستگی ذهنی",
    "Physical fatigue"
        to "خستگی جسمی",
    "Sensitivity to physical fatigue is not set"
        to "حساسیت به خستگی جسمی تعیین نشده",
    "Physical fatigue threshold is not set"
        to "خط خستگی جسمی تعیین نشده",
    "Safe physical fatigue line is not set"
        to "خط امن خستگی جسمی تعیین نشده",
    "Physical recovery while resting is not set"
        to "بازیابی جسمی در حال استراحت تعیین نشده",
    "How much a tired mind makes physical effort cost is not set"
        to "اثر ذهن خسته بر تلاش جسمی تعیین نشده",
    "{}: physical load is not set"
        to "{}: بار جسمی تعیین نشده",
    "{}: physical recovery is not set"
        to "{}: بازیابی جسمی تعیین نشده",
    "{}: the split between physical and mental fatigue was suggested by the app; confirm or change it"
        to "{}: تقسیم خستگی جسمی و ذهنی را اپ پیشنهاد داده؛ تأیید یا اصلاحش کن",

    // ---------------------------------------------- navigator stages 5 and 6
    "What the model proposed. Nothing has changed yet: open one to see what it is for, what it would change and why — and talk it over with the model before you decide."
        to "پیشنهادهای مدل. هنوز چیزی عوض نشده: هرکدام را باز کن تا ببینی برای چیست، چه چیزی را عوض می‌کند و چرا — و پیش از تصمیم، با مدل درباره‌اش حرف بزن.",
    "replaced by a revision"
        to "با نسخه‌ی تازه جایگزین شد",
    "A value refined by the model"
        to "عددی که مدل دقیق‌ترش کرد",
    "Revised after your conversation"
        to "بعد از گفتگوی تو بازنگری شد",
    "version"
        to "نسخه",
    "What it is for"
        to "برای چه بخشی است",
    "In Chronos"
        to "در Chronos",
    "What it would change"
        to "چه چیزی را عوض می‌کند",
    "Nothing: these are findings to read and questions to think about."
        to "هیچ: این‌ها یافته‌هایی برای خواندن و سؤال‌هایی برای فکر کردن‌اند.",
    "Changed from version"
        to "تغییر نسبت به نسخه‌ی",
    "What it does to your deadlines"
        to "اثرش روی مهلت‌هایت",
    "Measured by the app's own forecast, with and without this, on the same simulated futures. The model does not state these numbers."
        to "با پیش‌بینی خود اپ اندازه گرفته شده: با این تغییر و بدون آن، روی همان آینده‌های شبیه‌سازی‌شده. این عددها را مدل نمی‌گوید.",
    "On what basis"
        to "بر چه اساسی",
    "Answered by"
        to "پاسخ از",
    "The model saw a compact snapshot of your graph — dates, titles, owners, the forecast and this week's hours — never your log, and never the nodes that involve another person."
        to "مدل یک خلاصه‌ی فشرده از گرافت دیده — تاریخ‌ها، عنوان‌ها، مالک‌ها، پیش‌بینی و ساعت‌های این هفته — نه لاگت را، و نه گره‌هایی که به شخص دیگری مربوط‌اند.",
    "Talk it over with the model"
        to "با مدل درباره‌اش حرف بزن",
    "Ask why, say what you disagree with, or suggest a change. The model answers with the same snapshot in front of it; if it agrees, it revises the proposal and the new version waits for you here."
        to "بپرس چرا، بگو با چه چیزی موافق نیستی، یا تغییری پیشنهاد بده. مدل با همان خلاصه جلوی چشمش جواب می‌دهد؛ اگر قبول کند، پیشنهاد را بازنگری می‌کند و نسخه‌ی تازه همین‌جا منتظرت می‌ماند.",
    "The model is answering…"
        to "مدل در حال پاسخ دادن است…",
    "Why 6 hours? I have classes on Sunday and Tuesday."
        to "چرا ۶ ساعت؟ یکشنبه و سه‌شنبه کلاس دارم.",
    "Send"
        to "بفرست",
    "Each message is one call to the model, within your budget."
        to "هر پیام یک فراخوان مدل است، در حد بودجه‌ات.",
    "This version is no longer waiting: the model can still explain it, but not revise it."
        to "این نسخه دیگر منتظر نیست: مدل هنوز می‌تواند توضیحش بدهد، ولی بازنگری‌اش نمی‌کند.",
    "with it"
        to "با آن",
    "Let the model estimate it"
        to "بگذار مدل تخمینش بزند",
    "Let the model estimate all of these"
        to "بگذار مدل همه‌ی این‌ها را تخمین بزند",
    "Each becomes a proposal you can open, question and approve. Those that do not fit in today's calls wait in the queue for the next ones."
        to "هرکدام پیشنهادی می‌شود که می‌توانی بازش کنی، درباره‌اش بپرسی و تأییدش کنی. آن‌هایی که در فراخوان‌های امروز جا نشوند در صف منتظر بعدی‌ها می‌مانند.",
    "How these came about"
        to "این‌ها چطور به اینجا رسیدند",
    "Nothing has moved yet: log a few sessions."
        to "هنوز چیزی تکان نخورده: چند جلسه ثبت کن.",
    "Time against plan"
        to "زمان نسبت به برنامه",
    "Goal hours on a day of this type"
        to "ساعت هدف‌دار در روزی از این نوع",
    "What moved it, day by day"
        to "چه چیزی روز به روز جابه‌جایش کرد",
    "Followed by a Kalman filter: each day's sessions pull the estimate toward what they showed, recent days most, and a steady trend carries it on between sessions."
        to "با فیلتر کالمن دنبال می‌شود: جلسه‌های هر روز تخمین را به سمت چیزی که نشان دادند می‌کشند، روزهای اخیر بیشتر از همه، و یک روند ثابت آن را بین جلسه‌ها پیش می‌برد.",
    "Each planned day counts as the share of its planned time you worked; a day's weight halves every week."
        to "هر روز برنامه‌ریزی‌شده به اندازه‌ی سهم زمانی که کار کردی حساب می‌شود؛ وزن هر روز هر هفته نصف می‌شود.",
    "Goal hours on the days you ticked as this type, against the figure you stated."
        to "ساعت هدف‌دار در روزهایی که این نوع تیک زدی، در برابر عددی که خودت گفته بودی.",
    "before"
        to "قبل",
    "all goal work"
        to "همه‌ی کارهای هدف‌دار",
    "no session that day: the trend carried the estimate on"
        to "آن روز جلسه‌ای نبود: روند، تخمین را جلو برد",

    // ---------------------------------------------- navigator stages 5 and 6
    "Measured when it was proposed"
        to "اندازه‌گیری‌شده هنگام پیشنهاد",
    "by the app's own forecast, with and without it, on the same simulated futures. The model does not state these numbers; the node cards show tonight's newer forecast."
        to "با پیش‌بینی خود اپ، با این تغییر و بدون آن، روی همان آینده‌های شبیه‌سازی‌شده. این عددها را مدل نمی‌گوید؛ کارت گره‌ها پیش‌بینی تازه‌تر امشب را نشان می‌دهند.",
    "Ask why, or say what you would change…"
        to "بپرس چرا، یا بگو چه چیزی را عوض می‌کردی…",

    // ---------------------------------------------- navigator stages 5 and 6
    "14 changes ago"
        to "۱۴ تغییر پیش",
    "your prior"
        to "پیشین تو",

    // ---------------------------------------------- the Navigator in control
    "Tell the Navigator"
        to "به ناوبر بگو",
    "Say what is going on — a task your advisor gave you, a change of plans, something you want. A crew of models works out what to change in your goals and in Chronos; the app measures it, and you approve the parts you want. Nothing changes before that."
        to "بگو چه خبر است — کاری که استادت داده، تغییری در برنامه‌ها، چیزی که می‌خواهی. گروهی از مدل‌ها بررسی می‌کنند چه چیزی در هدف‌هایت و در کرونوس باید عوض شود؛ برنامه اثرش را می‌سنجد و تو بخش‌هایی را که می‌خواهی تأیید می‌کنی. پیش از آن هیچ چیز عوض نمی‌شود.",
    "The crew has no models yet: set it up in the LLM panel (Settings → The crew)."
        to "گروه هنوز مدلی ندارد: در پنل LLM تنظیمش کن (تنظیمات ← گروه).",
    "Earlier messages"
        to "پیام‌های قبلی",
    "Open the package"
        to "باز کردن بسته",
    "e.g. My advisor wants a literature review of {0} papers by {1} November."
        to "مثلاً: استادم تا {1} نوامبر مرور ادبیاتی از {0} مقاله می‌خواهد.",
    "Review the week now"
        to "همین حالا هفته را بررسی کن",
    "Free requests left today"
        to "درخواست‌های رایگان باقی‌ماندهٔ امروز",
    "a message takes about {0} to {1}, a weekly review {2} to {3}."
        to "هر پیام حدود {0} تا {1} و هر بررسی هفتگی {2} تا {3} درخواست می‌برد.",
    "Clear the conversation"
        to "پاک کردن گفتگو",
    "The crew is reviewing the week"
        to "گروه دارد هفته را بررسی می‌کند",
    "The crew is working on it"
        to "گروه دارد رویش کار می‌کند",
    "the app is measuring"
        to "برنامه دارد اثر را می‌سنجد",
    "Coordinator"
        to "هماهنگ‌کننده",
    "Architect"
        to "معمار گراف",
    "Log analyst"
        to "تحلیلگر لاگ",
    "Estimator"
        to "تخمین‌زن",
    "Week tuner"
        to "تنظیم‌کنندهٔ هفته",
    "Critic"
        to "منتقد",
    "Decides what reaches you, answers the critic, may ask once for a revision."
        to "تصمیم می‌گیرد چه چیزی به تو برسد، به منتقد جواب می‌دهد و یک بار می‌تواند اصلاح بخواهد.",
    "Turns what you tell it into tasks, dependencies and planner changes."
        to "حرف‌هایت را به کار، وابستگی و تغییر در برنامه‌ریز تبدیل می‌کند.",
    "Reads the summary of your log and says what it shows."
        to "خلاصهٔ لاگت را می‌خواند و می‌گوید چه نشان می‌دهد.",
    "Checks the hours of new tasks against your measured speed."
        to "ساعت کارهای جدید را با سرعت اندازه‌گیری‌شده‌ات می‌سنجد.",
    "Sets the week's levers: pressure, sleep, rest days, activities."
        to "اهرم‌های هفته را تنظیم می‌کند: فشار، خواب، روزهای استراحت، فعالیت‌ها.",
    "Looks for what is wrong or risky in a proposal and its measured effect."
        to "دنبال اشکال‌ها و ریسک‌های یک پیشنهاد و اثر سنجیده‌اش می‌گردد.",
    "The crew"
        to "گروه",
    "Each role is played by its own model. OpenRouter's free models cost nothing; the coordinator may be a stronger provider you set up above (Gemini, Claude). If a role's model does not answer, the next free one takes over."
        to "هر نقش را مدل خودش بازی می‌کند. مدل‌های رایگان OpenRouter هزینه‌ای ندارند؛ هماهنگ‌کننده می‌تواند ارائه‌دهندهٔ قوی‌تری باشد که بالا تنظیم کرده‌ای (Gemini، Claude). اگر مدل یک نقش جواب ندهد، مدل رایگان بعدی جایش را می‌گیرد.",
    "Choose models automatically"
        to "انتخاب خودکار مدل‌ها",
    "First add OpenRouter as a provider (Add a provider → OpenRouter) with your key."
        to "اول OpenRouter را با کلیدت به‌عنوان ارائه‌دهنده اضافه کن (افزودن ارائه‌دهنده ← OpenRouter).",
    "Fetch the free models again"
        to "دوباره گرفتن فهرست مدل‌های رایگان",
    "Free OpenRouter requests a day"
        to "درخواست رایگان OpenRouter در روز",
    "{0} while you have bought less than {1} dollars of OpenRouter credit in all; {2} once you have (the free models still cost nothing). OpenRouter also allows {3} a minute; the app keeps to it."
        to "{0} تا وقتی در مجموع کمتر از {1} دلار اعتبار OpenRouter خریده‌ای؛ {2} وقتی خریده باشی (مدل‌های رایگان باز هم هزینه‌ای ندارند). OpenRouter در دقیقه هم {3} درخواست اجازه می‌دهد و برنامه رعایتش می‌کند.",
    "You said"
        to "تو گفتی",
    "The Navigator"
        to "ناوبر",
    "The changes"
        to "تغییرها",
    "Each change is approved or left out as a whole. The odds and the week below were measured for all of them together."
        to "هر تغییر یکجا تأیید یا کنار گذاشته می‌شود. احتمال‌ها و هفتهٔ پایین برای همهٔ آن‌ها با هم سنجیده شده‌اند.",
    "Refused by the app's checks"
        to "ردشده در بررسی‌های برنامه",
    "What it needs to know from you"
        to "چیزهایی که باید از تو بداند",
    "Answer in the conversation below or in Tell the Navigator."
        to "در گفتگوی پایین یا در «به ناوبر بگو» جواب بده.",
    "Your goals' odds"
        to "احتمال رسیدن به هدف‌هایت",
    "No deadline's odds moved."
        to "احتمال هیچ ددلاینی تغییر نکرد.",
    "A new goal"
        to "هدف جدید",
    "Below its floor of"
        to "زیر حداقلش،",
    "at the low end of the band"
        to "در پایین بازه",
    "The middle figure is the chance of meeting the deadline; the range is the 80% band — how far that chance could be off while the log has not pinned your speed, adherence and goal hours down. Critical and important goals must keep the low end above their floor, or the package must say what makes up for it."
        to "عدد وسط احتمال رسیدن به ددلاین است؛ بازه، بازهٔ ۸۰٪ است — یعنی تا وقتی لاگ سرعت، پایبندی و ساعت‌های کاری‌ات را دقیق مشخص نکرده، آن احتمال تا کجا ممکن است جابه‌جا باشد. هدف‌های حیاتی و مهم باید پایین بازه‌شان بالای حداقل بماند، وگرنه بسته باید بگوید چه چیزی جبرانش می‌کند.",
    "The coming week in Chronos"
        to "هفتهٔ پیش رو در کرونوس",
    "Goal hours"
        to "ساعت کار روی هدف‌ها",
    "Hours that could not be placed"
        to "ساعت‌هایی که جا نشدند",
    "Minutes above the mental line"
        to "دقیقه‌های بالای خط خستگی ذهنی",
    "Minutes above the physical line"
        to "دقیقه‌های بالای خط خستگی جسمی",
    "Chronos planned the next seven days with and without the package, and arranged each day; tiredness is what its model of you expects."
        to "کرونوس هفت روز آینده را با بسته و بدون آن برنامه‌ریزی کرد و هر روز را چید؛ خستگی همان است که مدلش از تو انتظار دارد.",
    "What each role said"
        to "هر نقش چه گفت",
    "Show"
        to "نمایش",
    "The coordinator's answers to the critic"
        to "پاسخ‌های هماهنگ‌کننده به منتقد",
    "Approving writes the ticked changes into your goal graph and into Chronos, and rebuilds the plan. Archived activities keep their log and can be brought back in Setup."
        to "تأیید، تغییرهای علامت‌خورده را در گراف هدف‌ها و کرونوس می‌نویسد و برنامه را دوباره می‌سازد. فعالیت‌های بایگانی‌شده لاگشان را نگه می‌دارند و در «تنظیمات» برمی‌گردند.",
    "Critical"
        to "حیاتی",
    "Important"
        to "مهم",
    "Optional"
        to "اختیاری",
    "How much it matters"
        to "چقدر مهم است",
    "default"
        to "پیش‌فرض",
    "A change may not push its odds below"
        to "هیچ تغییری نباید احتمالش را پایین‌تر ببرد از",
    "(low end of the {0}% band)"
        to "(پایین بازهٔ {0}٪)",
    "Each role has a model. Change any of them below."
        to "هر نقش یک مدل دارد. هر کدام را خواستی پایین عوض کن.",
    "The crew is reviewing the week; its package arrives in the inbox."
        to "گروه دارد هفته را بررسی می‌کند؛ بسته‌اش به صندوق پیشنهادها می‌آید.",
    "Tick at least one change."
        to "دست‌کم یک تغییر را علامت بزن.",
    "Approved {0} of {1} changes."
        to "{0} از {1} تغییر تأیید شد.",
    "Approved {0} of {1} changes; Chronos was changed ({2})."
        to "{0} از {1} تغییر تأیید شد؛ کرونوس تغییر کرد ({2}).",
    "Approved {0} of {1} changes. The coming week was planned again: check it and accept."
        to "{0} از {1} تغییر تأیید شد. هفتهٔ پیش رو دوباره برنامه‌ریزی شد: بررسی و قبولش کن.",
    "Approved {0} of {1} changes; Chronos was changed ({2}). The coming week was planned again: check it and accept."
        to "{0} از {1} تغییر تأیید شد؛ کرونوس تغییر کرد ({2}). هفتهٔ پیش رو دوباره برنامه‌ریزی شد: بررسی و قبولش کن.",
    "Not a crew proposal."
        to "این پیشنهاد از طرف گروه نیست.",
    "Set up the crew's models in the LLM panel first."
        to "اول مدل‌های گروه را در پنل LLM تنظیم کن.",
    "Only {0} free OpenRouter requests are left today; this needs about {1}. Try tomorrow."
        to "امروز فقط {0} درخواست رایگان OpenRouter مانده و این کار حدود {1} تا لازم دارد. فردا امتحان کن.",
    "Add an OpenRouter provider (with its key) first."
        to "اول یک ارائه‌دهندهٔ OpenRouter (با کلیدش) اضافه کن.",
    "OpenRouter lists no free models right now."
        to "OpenRouter الان هیچ مدل رایگانی فهرست نکرده است.",
    "No network: a conversation needs it."
        to "اینترنت نیست: گفتگو به آن نیاز دارد.",
    "Nothing to change."
        to "چیزی برای تغییر نیست.",
    "The week's levers stay as they are."
        to "اهرم‌های هفته همان‌طور می‌مانند.",
    "You told the Navigator"
        to "به ناوبر گفتی",
    "Weekly review of the levers"
        to "بررسی هفتگی اهرم‌ها",
    "The architect's answer could not be used: {}"
        to "پاسخ معمار قابل استفاده نبود: {}",
    "The tuner's answer could not be used: {}"
        to "پاسخ تنظیم‌کننده قابل استفاده نبود: {}",
    "The coordinator's answer could not be used: {}"
        to "پاسخ هماهنگ‌کننده قابل استفاده نبود: {}",
    "New task {}: {} ({}–{} h, due {})"
        to "کار جدید {}: {} ({} تا {} ساعت، موعد {})",
    "{} now waits for {}"
        to "{} حالا منتظر {} می‌ماند",
    "Chronos: a goal \"{}\" of {} h by {}, worked as \"{}\" (set up like \"{}\")"
        to "کرونوس: هدف «{}» به اندازهٔ {} ساعت تا {}، با فعالیت «{}» (تنظیم‌شده مثل «{}»)",
    "Chronos: a new activity for {} \"{}\" on {}"
        to "کرونوس: فعالیت جدید برای {} با نام «{}» در روزهای {}",
    "Chronos: a new activity for {} \"{}\""
        to "کرونوس: فعالیت جدید برای {} با نام «{}»",
    "Chronos: a new upkeep activity \"{}\" on {}"
        to "کرونوس: کار روزمرهٔ جدید «{}» در روزهای {}",
    "Chronos: a new upkeep activity \"{}\""
        to "کرونوس: کار روزمرهٔ جدید «{}»",
    "Chronos: {} is archived — no longer planned; its log stays"
        to "کرونوس: {} بایگانی می‌شود — دیگر برنامه‌ریزی نمی‌شود؛ لاگش می‌ماند",
    "Chronos: {} is planned again"
        to "کرونوس: {} دوباره برنامه‌ریزی می‌شود",
    "Chronos: share of free time left unscheduled {} → {}"
        to "کرونوس: سهم وقت آزادِ برنامه‌ریزی‌نشده {} ← {}",
    "Chronos: mental tiredness line {} → {}"
        to "کرونوس: خط خستگی ذهنی {} ← {}",
    "Chronos: physical tiredness line {} → {}"
        to "کرونوس: خط خستگی جسمی {} ← {}",
    "Chronos: sleep {} → {}"
        to "کرونوس: خواب {} ← {}",
    "Chronos: {} becomes a rest day — no goal work"
        to "کرونوس: {} روز استراحت می‌شود — بدون کار روی هدف‌ها",
    "Chronos: {} is a working day again"
        to "کرونوس: {} دوباره روز کاری می‌شود",
    "Chronos: {} — {}"
        to "کرونوس: {} — {}",
    "{} counts as critical (was {})"
        to "{} حیاتی حساب می‌شود (قبلاً {})",
    "{} counts as important (was {})"
        to "{} مهم حساب می‌شود (قبلاً {})",
    "{} counts as optional (was {})"
        to "{} اختیاری حساب می‌شود (قبلاً {})",

    // ---------------------------------------------- the Navigator in control
    "archived"
        to "بایگانی‌شده",
    "Archived"
        to "بایگانی‌شده",
    "Not planned on any day, but kept with its log. The Navigator archives instead of deleting."
        to "در هیچ روزی برنامه‌ریزی نمی‌شود، ولی با لاگش نگه داشته می‌شود. ناوبر به‌جای حذف، بایگانی می‌کند.",

    // ---------------------------------------------- the Navigator in control
    "OpenRouter cannot be reached from this network (HTTP {0}). Turn on a VPN and try again."
        to "از این شبکه به OpenRouter دسترسی نیست (HTTP {0}). VPN را روشن کن و دوباره امتحان کن.",
    "Used when you ask, when a trigger fires (see Automatic checks), and by the crew. It never sees your log itself — the crew gets the app's summary of it — and what it says is a proposal you approve or reject."
        to "وقتی خودت می‌پرسی، وقتی یک بررسی خودکار فعال می‌شود و توسط گروه استفاده می‌شود. خود لاگت را هرگز نمی‌بیند — گروه فقط خلاصه‌ای را که برنامه می‌سازد می‌گیرد — و هرچه بگوید پیشنهادی است که تأیید یا رد می‌کنی.",

    // ---------------------------------------------- the Navigator in control
    "The key saved for {} is not an OpenRouter key (those start with \"sk-or-\"). Paste the key from openrouter.ai/keys in the LLM panel ({} → Edit)."
        to "کلیدی که برای {} ذخیره شده کلید OpenRouter نیست (کلیدهای OpenRouter با «sk-or-» شروع می‌شوند). کلید را از openrouter.ai/keys بردار و در پنل LLM وارد کن ({} ← ویرایش).",

    // ---------------------------------------------- the Navigator in control
    "Chronos would stop planning until this is fixed in Setup"
        to "کرونوس تا وقتی این در «تنظیمات» درست نشود برنامه‌ریزی نمی‌کند",

    // ---------------------------------------------- the Navigator in control
    "Conversation"
        to "گفتگو",
    "Inbox"
        to "صندوق پیشنهادها",
    "This week"
        to "این هفته",
    "Needs your answer"
        to "نیاز به پاسخ تو",
    "Learning"
        to "یادگیری",
    "No goal graph yet: import one in Settings."
        to "هنوز گراف هدفی نیست: در «تنظیمات» واردش کن.",
    "Open Settings"
        to "باز کردن تنظیمات",
    "nothing waiting"
        to "چیزی منتظر نیست",
    "not planned"
        to "برنامه‌ریزی نشده",
    "accepted"
        to "پذیرفته‌شده",
    "to accept"
        to "منتظر پذیرش",
    "Goals at risk"
        to "هدف‌های در خطر",
    "all above their minimum"
        to "همه بالای حداقل",
    "below their minimum"
        to "زیر حداقل",
    "nothing open"
        to "چیزی باز نیست",
    "open items"
        to "مورد باز",
    "Goal graph, LLM and crew, automatic checks, parameters"
        to "گراف هدف، مدل‌ها و گروه، بررسی‌های خودکار، پارامترها",
    "Full conversation"
        to "کل گفتگو",
    "Weekly review"
        to "بررسی هفتگی",
    "Package"
        to "بسته",
    "no longer in the inbox"
        to "دیگر در صندوق نیست",
    "change"
        to "تغییر",
    "changes"
        to "تغییر",
    "Open to review and approve"
        to "باز کن، بررسی و تأیید کن",
    "goal below its minimum"
        to "هدف زیر حداقلش",
    "goals below their minimum"
        to "هدف زیر حداقلشان",
    "no goal pushed below its minimum"
        to "هیچ هدفی زیر حداقل نمی‌رود",
    "goal hours this week"
        to "ساعت کار روی هدف‌ها این هفته",
    "above the mental line"
        to "بالای خط خستگی ذهنی",
    "Chronos would stop planning"
        to "کرونوس برنامه‌ریزی را متوقف می‌کند",
    "goals moved"
        to "هدف جابه‌جا شد",
    "roles"
        to "نقش",
    "Each change is approved or left out as a whole."
        to "هر تغییر یکجا تأیید یا کنار گذاشته می‌شود.",
    "Nothing has been proposed yet."
        to "هنوز پیشنهادی نیامده است.",

    // ---------------------------------------------- the Navigator in control
    "Paused by the Navigator"
        to "متوقف‌شده توسط ناوبر",
    "End the pause"
        to "پایان توقف",
    "paused"
        to "متوقف",
    "Chronos: {} is not planned from {} to {}"
        to "کرونوس: {} از {} تا {} برنامه‌ریزی نمی‌شود",

    // ---------------------------------------------- the Navigator in control
    "Not sent"
        to "ارسال نشد",
    "Send again"
        to "ارسال دوباره",
    "Copy"
        to "کپی",

    // ---------------------------------------------- the Navigator in control
    "Not sent: {}"
        to "ارسال نشد: {}",

    // ---------------------------------------------- the Navigator in control
    "Ask through"
        to "پرسیدن از طریق",
    "My chat app"
        to "اپ چت خودم",
    "The app makes one complete question; you send it to Claude, ChatGPT or Gemini, copy the whole reply, and bring it back here. It is checked and measured like any answer. No limits, no busy providers."
        to "برنامه یک سؤال کامل می‌سازد؛ تو آن را به Claude، ChatGPT یا Gemini می‌فرستی، کل جوابش را کپی می‌کنی و این‌جا برمی‌گردانی. مثل هر جوابی بررسی و سنجیده می‌شود. بدون محدودیت و شلوغی.",
    "Your question"
        to "سؤال تو",
    "being prepared"
        to "در حال آماده‌شدن",
    "ready for your chat app"
        to "آماده برای اپ چت تو",
    "1. Send it to your chat app (or copy it).  2. When it answers, copy its whole reply.  3. Paste it below."
        to "۱. آن را به اپ چتت بفرست (یا کپی کن).  ۲. وقتی جواب داد، کل جوابش را کپی کن.  ۳. پایین جای‌گذاری کن.",
    "Send to a chat app"
        to "ارسال به اپ چت",
    "Copy the question"
        to "کپی سؤال",
    "Paste the chat's whole reply here"
        to "کل جواب چت را این‌جا بگذار",
    "Paste"
        to "جای‌گذاری",
    "Use this answer"
        to "استفاده از این جواب",
    "Send the question to"
        to "ارسال سؤال به",
    "The package is ready: open it to review and approve."
        to "بسته آماده است: بازش کن، بررسی و تأیید کن.",
    "The chat's answer changes nothing in your plan."
        to "جواب چت چیزی در برنامه‌ات عوض نمی‌کند.",
    "Cancelled before an answer came back"
        to "پیش از رسیدن جواب لغو شد",

    // ---------------------------------------------- the Navigator in control
    "1. Send it to your chat app.  2. When it answers, share its reply (long-press it → Share) to \"Chronos navigator\" — the package is made by itself. Or copy the whole reply and paste it below."
        to "۱. آن را به اپ چتت بفرست.  ۲. وقتی جواب داد، جوابش را (با نگه داشتن انگشت ← اشتراک‌گذاری) به «Chronos navigator» بفرست — بسته خودش ساخته می‌شود. یا کل جواب را کپی کن و پایین جای‌گذاری کن.",
    "No question is waiting for an answer: ask one first with \"My chat app\"."
        to "هیچ سؤالی منتظر جواب نیست: اول با «اپ چت خودم» سؤالی بپرس.",

    // ---------------------------------------------- the Navigator in control
    "A goal gathers the activities that serve it; each activity has its own target — a deadline and an amount, or hours a week."
        to "هر هدف فعالیت‌هایی را که به آن خدمت می‌کنند کنار هم می‌آورد؛ هر فعالیت هدف‌گذاری خودش را دارد — یک مقدار تا یک تاریخ، یا ساعت در هفته.",
    "A goal gathers the activities that serve it. Each activity has its own target: an amount by a date, or hours a week."
        to "هر هدف فعالیت‌هایی را که به آن خدمت می‌کنند کنار هم می‌آورد. هر فعالیت هدف‌گذاری خودش را دارد: یک مقدار تا یک تاریخ، یا ساعت در هفته.",
    "no activity yet"
        to "هنوز فعالیتی ندارد",
    "There is already a goal with this name."
        to "هدفی با این نام هست.",
    "Next: its first activity"
        to "بعدی: اولین فعالیتش",
    "Remove this goal and its activities"
        to "حذف این هدف و فعالیت‌هایش",
    "Its activities are removed too; their log stays."
        to "فعالیت‌هایش هم حذف می‌شوند؛ لاگشان می‌ماند.",
    "Target"
        to "هدف‌گذاری",
    "What kind of target?"
        to "چه نوع هدف‌گذاری؟",
    "Choose the goal it serves first."
        to "اول هدفی را که به آن خدمت می‌کند انتخاب کن.",
    "An ongoing target never finishes; this is what paces it."
        to "هدف‌گذاری مداوم تمام نمی‌شود؛ این است که سرعتش را تعیین می‌کند.",
    "For ongoing work one unit is one hour, so this is usually 1."
        to "برای کار مداوم هر واحد یک ساعت است، پس معمولاً ۱ است.",
    "Hard"
        to "سخت",
    "Must be met whatever else happens; a soft target gives way first."
        to "باید هر طور شده انجام شود؛ هدف‌گذاری نرم زودتر کنار می‌رود.",
    "Compare activities two at a time instead of assigning numbers. What comes out is what the scheduler trades against fatigue."
        to "به‌جای عدد دادن، فعالیت‌ها را دوبه‌دو مقایسه کن. نتیجه همان چیزی است که برنامه‌ریز با خستگی معاوضه می‌کند.",
    "Not enough activities to compare"
        to "فعالیت کافی برای مقایسه نیست",
    "Add at least two goal activities in Setup and they will appear here."
        to "در «تنظیمات» دست‌کم دو فعالیت هدف اضافه کن تا این‌جا بیایند.",
    "Not compared now"
        to "فعلاً مقایسه نمی‌شوند",
    "These no longer need a priority. Move a deadline later or raise an amount in Setup and the activity comes back here."
        to "این‌ها دیگر به اولویت نیاز ندارند. اگر در «تنظیمات» مهلتی را عقب بیندازی یا مقداری را بیشتر کنی، آن فعالیت دوباره این‌جا می‌آید.",
    "deadline passed"
        to "مهلت گذشته",
    "Already learned from: switching this takes back (or gives back) what this day taught, and the nights after it are learned again."
        to "از این روز یاد گرفته شده: با تغییر این کلید، آنچه این روز یاد داده پس گرفته (یا دوباره داده) می‌شود و شب‌های بعد از آن دوباره یاد گرفته می‌شوند.",
    "How the day was"
        to "آن روز چطور بود",
    "Marked unusual: kept in the log, not learned from."
        to "غیرعادی علامت خورده: در لاگ می‌ماند، ولی از آن یاد گرفته نمی‌شود.",
    "Saved. Nothing the model learned depended on that day."
        to "ذخیره شد. هیچ‌کدام از آموخته‌های مدل به آن روز وابسته نبود.",
    "That day is no longer learned from; what it taught was taken back. The Horizon tab offers a rebuild if the plan moved."
        to "دیگر از آن روز یاد گرفته نمی‌شود و آنچه یاد داده بود پس گرفته شد. اگر برنامه جابه‌جا شده باشد، زبانهٔ افق بازسازی را پیشنهاد می‌دهد.",
    "That day is learned from again. The Horizon tab offers a rebuild if the plan moved."
        to "دوباره از آن روز یاد گرفته می‌شود. اگر برنامه جابه‌جا شده باشد، زبانهٔ افق بازسازی را پیشنهاد می‌دهد.",
    "Re-checking…"
        to "در حال بررسی…",
    "Re-check against now"
        to "بررسی مجدد با وضعیت فعلی",
    "Also ask the model about older ones"
        to "قدیمی‌ترها را از مدل هم بپرس",
    "calls"
        to "درخواست",
    "Proposals whose subject is finished or gone, whose period or date has passed, or that refer to a Chronos activity that no longer exists leave the inbox as outdated; the rest get today's measured effect."
        to "پیشنهادهایی که موضوعشان تمام یا حذف شده، بازه یا تاریخشان گذشته، یا به فعالیتی در کرونوس اشاره دارند که دیگر وجود ندارد، به‌عنوان «منسوخ» از صندوق بیرون می‌روند؛ اثر بقیه با پیش‌بینی امروز دوباره سنجیده می‌شود.",
    "No longer applies"
        to "دیگر کاربرد ندارد",
    "Put it back in the inbox"
        to "برگرداندن به صندوق",
    "outdated"
        to "منسوخ",
    "The model says it no longer helps; it left the inbox."
        to "مدل می‌گوید این پیشنهاد دیگر کمکی نمی‌کند؛ از صندوق بیرون رفت.",
    "Tap an empty hour to add a class there; tap a class to change it."
        to "روی یک ساعت خالی بزن تا کلاسی آن‌جا اضافه شود؛ روی کلاس بزن تا تغییرش دهی.",
    "These overlap; only one can happen at a time"
        to "این‌ها هم‌زمان‌اند؛ فقط یکی می‌تواند برگزار شود",
    "session"
        to "جلسه",
    "at home"
        to "در خانه",
    "Class"
        to "کلاس",
    "A new class at this time"
        to "کلاس تازه در این ساعت",
    "Or another session of:"
        to "یا جلسهٔ دیگری از:",
    "Same time, another day"
        to "همین ساعت، روز دیگر",
    "Load"
        to "بار",
    "Your quick answers"
        to "پاسخ‌های کوتاهت",
    "Waiting to be read by the model (it needs the network)."
        to "منتظر خوانده شدن توسط مدل (اینترنت لازم است).",
    "Kept as you wrote it: fill in the boxes below."
        to "همان‌طور که نوشتی نگه داشته شد: جعبه‌های پایین را پر کن.",
    "Not read"
        to "خوانده نشد",
    "Fill in the boxes below, or send it again."
        to "جعبه‌های پایین را پر کن، یا دوباره بفرست.",
    "The model found nothing to log in it."
        to "مدل چیزی برای ثبت در آن پیدا نکرد.",
    "Keep the ticked ones"
        to "ثبت موارد تیک‌خورده",
    "Your answer"
        to "پاسخت",
    "A correction, if any"
        to "اصلاح، اگر لازم است",
    "Discard"
        to "دور انداختن",
    "units"
        to "واحد",
    "focus"
        to "تمرکز",
    "restored"
        to "بازیابی",
    "Log reminders"
        to "یادآور ثبت لاگ",
    "At the times below, and whenever several waking hours pass with nothing logged, a notification asks how it is going — never while you sleep or are in a class. Answer it in one line and the model turns it into entries for you to keep, or open the Log."
        to "در ساعت‌های زیر، و هر وقت چند ساعت بیداری بگذرد و چیزی ثبت نشده باشد، یک اعلان حالت را می‌پرسد — هرگز وقت خواب یا سر کلاس. در یک خط جواب بده تا مدل آن را به ورودی‌هایی برای تأیید تو تبدیل کند، یا لاگ را باز کن.",
    "Remind me"
        to "یادآوری کن",
    "Notifications are blocked for Chronos: allow them to get reminders."
        to "اعلان‌های کرونوس مسدود است: برای گرفتن یادآور اجازه بده.",
    "Ask at"
        to "بپرس در ساعت",
    "Add a time"
        to "افزودن ساعت",
    "Ask after this long with nothing logged"
        to "پرسیدن بعد از این مدت بدون ثبت",
    "never"
        to "هرگز",
    "h"
        to "ساعت",
    "Let the model read my quick answers"
        to "پاسخ‌های کوتاهم را مدل بخواند",
    "One call each, from the provider and budget in the Navigator's LLM settings. Off: the answer is only kept for you to fill in."
        to "هر پاسخ یک درخواست، از سرویس‌دهنده و بودجهٔ تنظیمات LLM ناوبر. خاموش: پاسخ فقط نگه داشته می‌شود تا خودت پر کنی.",
    "Or write it here: what you did and how you feel"
        to "یا همین‌جا بنویس: چه کردی و حالت چطور است",
    "Try a reminder now"
        to "امتحان یادآور همین حالا",
    "Nothing of it is planned after this hour on the deadline day."
        to "روز مهلت، بعد از این ساعت چیزی از آن برنامه‌ریزی نمی‌شود.",
    "Whole day"
        to "تمام روز",
    "Nothing of it is planned before this hour on its first day."
        to "در روز اولش، قبل از این ساعت چیزی از آن برنامه‌ریزی نمی‌شود.",
    "Last day"
        to "روز آخر",
    "No end"
        to "بدون پایان",
    "Planned with no end. Give it a last day when its period is over — its log is kept, it is just not planned after that."
        to "بدون پایان برنامه‌ریزی می‌شود. وقتی دوره‌اش تمام شد، روز آخرش را بده — لاگش می‌ماند، فقط بعد از آن برنامه‌ریزی نمی‌شود.",
    "Its period is over"
        to "دوره‌اش تمام شده",
    "Ended"
        to "تمام‌شده",
    "not planned any more; its log and learned values are kept."
        to "دیگر برنامه‌ریزی نمی‌شود؛ لاگ و مقدارهای یادگرفته‌اش نگه داشته می‌شوند.",
    "Planned up to and including this day, not after it."
        to "تا همین روز (با خودش) برنامه‌ریزی می‌شود، نه بعد از آن.",
    "Periods that are over"
        to "دوره‌های تمام‌شده",
    "until"
        to "تا",
    "From an hour on that day"
        to "از ساعت مشخصی در آن روز",
    "Nothing is placed before this hour on the first day."
        to "در روز اول، قبل از این ساعت چیزی چیده نمی‌شود.",
    "The whole first day is planned."
        to "تمام روز اول برنامه‌ریزی می‌شود.",
    "A question about the graph"
        to "سؤال دربارهٔ گراف",
    "Ask why two goals are linked, where a chance comes from, or what would raise it. The model sees the whole graph with today's odds; if you ask it to correct a link, an estimate or a chance, the correction comes as a package to approve."
        to "بپرس چرا دو هدف به هم وصل‌اند، یک احتمال از کجا می‌آید، یا چه چیزی بالایش می‌برد. مدل کل گراف را با احتمال‌های امروز می‌بیند؛ اگر بخواهی اتصال، تخمین یا احتمالی را اصلاح کند، اصلاح به‌صورت یک بسته برای تأیید می‌آید.",
    "e.g. Why is the chance of my master's application so low?"
        to "مثلاً: چرا احتمال پذیرش ارشدم این‌قدر پایین است؟",
    "Done and past"
        to "تمام‌شده و گذشته",
    "Move a deadline later or raise an amount (in Setup) and it comes back above."
        to "اگر مهلت را عقب بیندازی یا مقدار را بیشتر کنی (در تنظیمات)، دوباره بالا برمی‌گردد.",
    "What was arranged before this hour today stays as it was; only the rest of the day is arranged again. Later days are planned whole."
        to "آنچه امروز پیش از این ساعت چیده شده بود همان‌طور می‌ماند؛ فقط بقیهٔ روز دوباره چیده می‌شود. روزهای بعد کامل برنامه‌ریزی می‌شوند.",
    "Change it in the Scheduler"
        to "تغییرش در زمان‌بند",
    "Go to upkeep"
        to "رفتن به نگهداشت",
    "Change it in Setup"
        to "تغییرش در تنظیمات",
    "teaches this session's mental load and recovery"
        to "بار و بازیابی ذهنی همین جلسه را یاد می‌دهد",
    "teaches this session's physical load and recovery"
        to "بار و بازیابی جسمی همین جلسه را یاد می‌دهد",
    "Saved"
        to "ذخیره شد",
    "The plan beside what was done"
        to "برنامه کنار آنچه انجام شد",
    "Planned"
        to "برنامه",
    "Nothing logged yet: fill in the boxes below and what you did appears beside the plan."
        to "هنوز چیزی ثبت نشده: جعبه‌های پایین را پر کن تا آنچه انجام دادی کنار برنامه بیاید.",
    "Your waking hours, Saturday to Friday. Solid: fixed times. Outlined: where an activity is allowed. Tap one to open it."
        to "ساعت‌های بیداری‌ات، شنبه تا جمعه. پُر: زمان‌های ثابت. خط‌دار: جایی که فعالیت مجاز است. برای باز کردن رویش بزن.",
    "Planned week"
        to "هفتهٔ برنامه‌ریزی‌شده",
    "No plan yet: build one on the Horizon tab and the coming week shows here."
        to "هنوز برنامه‌ای نیست: در زبانهٔ افق بساز تا هفتهٔ پیش رو این‌جا بیاید.",
    "The coming seven days as they are arranged. Tap a block to open what it belongs to."
        to "هفت روز پیش رو همان‌طور که چیده شده‌اند. برای باز کردن هر بلوک رویش بزن.",
    "Each activity on its own row: the pale bar is where it may be placed that day, a solid one a fixed time. Tap a row to open it."
        to "هر فعالیت در ردیف خودش: نوار کم‌رنگ جایی است که آن روز می‌تواند چیده شود، نوار پُر زمان ثابت. برای باز کردن روی ردیف بزن.",
    "Nothing is allowed on this day."
        to "در این روز چیزی مجاز نیست.",
    "Deadlines"
        to "مهلت‌ها",
    "all on track"
        to "همه در مسیر",
    "on track"
        to "در مسیر",
    "Next 7 days"
        to "۷ روز آینده",
    "of goal work planned"
        to "کار هدف برنامه‌ریزی‌شده",
    "Room left"
        to "جای خالی",
    "usable but unplanned this week"
        to "قابل‌استفاده ولی بی‌برنامه در این هفته",
    "Problems"
        to "مشکلات",
    "nothing left out"
        to "چیزی جا نماند",
    "to look at"
        to "برای بررسی",
    "The month"
        to "ماه پیش رو",
    "Each day coloured by how much of its usable time the plan fills. Tap a day for what it holds."
        to "رنگ هر روز نشان می‌دهد برنامه چقدر از وقت قابل‌استفاده‌اش را پر کرده. برای دیدن جزئیات روی روز بزن.",
    "Goals to their deadlines"
        to "هدف‌ها تا مهلتشان",
    "room left"
        to "جای خالی",
    "full"
        to "پر",
    "deadline"
        to "مهلت",
    "usable"
        to "قابل‌استفاده",
    "upkeep"
        to "نگهداشت",
    "travel"
        to "رفت‌وآمد",
    "No goal hours on this day."
        to "این روز ساعتی برای هدف‌ها ندارد.",
    "is due"
        to "مهلتش است",
    "No goal is waiting for hours in this horizon."
        to "هیچ هدفی در این بازه منتظر ساعت نیست.",
    "short"
        to "کسری",
    "Each row runs from today to its deadline (the red mark); the darker a day, the more hours the plan gives it. Tap a row to change the goal. Swipe sideways for the rest of the month."
        to "هر ردیف از امروز تا مهلتش (خط قرمز) است؛ هر روز تیره‌تر، ساعت بیشتری گرفته. برای تغییر هدف روی ردیف بزن. برای بقیهٔ ماه افقی بکش.",
    "What is done, and each deadline; edit them here."
        to "مقدار انجام‌شده و مهلت هر کدام؛ این‌جا ویرایششان کن.",
    "Every unit is marked done, so nothing of it is planned. If work is left, raise the total."
        to "همهٔ واحدها انجام‌شده ثبت شده‌اند، پس چیزی از آن برنامه‌ریزی نمی‌شود. اگر کاری مانده، مقدار کل را بیشتر کن.",
    "{} left"
        to "{} مانده",
    "How hard was it to stop?"
        to "دل کندن از آن چقدر سخت بود؟",
    "1 easy"
        to "۱ آسان",
    "5 could not stop"
        to "۵ نتوانستم دست بکشم",
    "teaches how stimulating it is"
        to "میزان تحریکش را یاد می‌دهد",
    "Getting into the next thing afterwards"
        to "شروع کار بعدی، بعد از آن",
    "5 very hard"
        to "۵ خیلی سخت",
    "Did you want more of it afterwards?"
        to "بعدش باز هم دلت می‌خواست؟",
    "1 not at all"
        to "۱ اصلاً",
    "5 a lot"
        to "۵ خیلی زیاد",
    "Film or series"
        to "فیلم یا سریال",
    "Film"
        to "فیلم",
    "Games"
        to "بازی",
    "Social media or short videos"
        to "شبکه‌های اجتماعی یا ویدیوی کوتاه",
    "Social media"
        to "شبکه‌های اجتماعی",
    "Music or a hobby"
        to "موسیقی یا سرگرمی",
    "Music"
        to "موسیقی",
    "Walk outside"
        to "قدم‌زدن بیرون",
    "Friends or family"
        to "دوستان یا خانواده",
    "Friends"
        to "دوستان",
    "New leisure"
        to "تفریح تازه",
    "Leisure"
        to "تفریح",
    "leisure"
        to "تفریح",
    "A rest the app chooses: never planned on its own, but picked for free time and breaks inside long work, as long as the day stays under your ceiling of stimulation."
        to "استراحتی که برنامه انتخاب می‌کند: هیچ‌وقت جداگانه برنامه‌ریزی نمی‌شود، بلکه برای وقت آزاد و استراحت وسط کارهای طولانی انتخاب می‌شود، به شرطی که روز از سقف تحریکت بالاتر نرود.",
    "At most per day"
        to "حداکثر در روز",
    "How stimulating"
        to "میزان تحریک",
    "calm"
        to "آرام",
    "very"
        to "زیاد",
    "Per hour, on the scale where your day's ceiling is about 1: a walk is low, short videos are high. A first guess; the log corrects it from how hard it was to stop and to start the next thing."
        to "در هر ساعت، با مقیاسی که سقف روزت حدود ۱ است: قدم‌زدن کم، ویدیوی کوتاه زیاد. این فقط حدس اول است؛ لاگ آن را از روی سختی دل کندن و سختی شروع کار بعدی اصلاح می‌کند.",
    "Learned from {} sessions"
        to "یادگرفته از {} جلسه",
    "Rests are chosen from these: what used to stay an open buffer, and a short break inside long work."
        to "استراحت‌ها از میان این‌ها انتخاب می‌شوند: جاهایی که قبلاً بافر آزاد می‌ماند، و یک استراحت کوتاه وسط کارهای طولانی.",
    "Ceiling of stimulation"
        to "سقف تحریک",
    "Daily ceiling"
        to "سقف روزانه",
    "strict"
        to "سخت‌گیر",
    "loose"
        to "آسان‌گیر",
    "The most stimulation a day may reach. No rest is planned that would take it past this."
        to "بیشترین تحریکی که یک روز می‌تواند به آن برسد. هیچ استراحتی برنامه‌ریزی نمی‌شود که از این بالاتر ببردش.",
    "How fast it fades"
        to "سرعت فروکش کردن",
    "½ h"
        to "½ ساعت",
    "4 h"
        to "۴ ساعت",
    "Hours for it to halve by itself; goal work uses it up twice as fast."
        to "چند ساعت طول می‌کشد تا خودبه‌خود نصف شود؛ کار روی هدف آن را دو برابر سریع‌تر مصرف می‌کند.",
    "How much it dulls goal work"
        to "چقدر کار هدف را کُند می‌کند",
    "a lot"
        to "زیاد",
    "Capacity taken from goal work per unit of stimulation still there."
        to "ظرفیتی که هر واحد تحریکِ باقی‌مانده از کار هدف کم می‌کند.",
    "Stimulation"
        to "تحریک",
    "What my log suggests"
        to "لاگم چه پیشنهادی دارد",
    "Allowed hours, days, session lengths and sleep, each with its reason; to approve or not"
        to "ساعت‌های مجاز، روزها، طول جلسه‌ها و خواب، هر کدام با دلیلش؛ برای تأیید یا رد",
    "From your log"
        to "از روی لاگت",
    "Your log shows nothing about hours, days, session lengths or sleep worth changing yet."
        to "لاگت هنوز چیزی دربارهٔ ساعت‌ها، روزها، طول جلسه‌ها یا خواب نشان نمی‌دهد که ارزش تغییر داشته باشد.",
    "What your log suggests is in the inbox, each change with its reason."
        to "پیشنهادهای لاگت در صندوق است، هر تغییر با دلیلش.",
    "Did you mean {}?"
        to "منظورت {} بود؟",
    "Use {}"
        to "{} بگذار",
    "A rest chosen from your leisure for free time; stimulation peaks at {} of your ceiling {}."
        to "استراحتی از میان تفریح‌هایت برای وقت آزاد؛ تحریک حداکثر به {} از سقف {} می‌رسد.",
    "A break inside {} h of goal work, chosen from your leisure; stimulation peaks at {} of your ceiling {}."
        to "استراحتی وسط {} ساعت کار هدف، از میان تفریح‌هایت؛ تحریک حداکثر به {} از سقف {} می‌رسد.",
    "{}: how stimulating it is has no first guess yet"
        to "{}: هنوز حدس اولی برای میزان تحریکش نداده‌ای",
    "stimulation per hour {} to {} ({} sessions: how hard to stop, to start the next thing, wanting more)"
        to "تحریک در ساعت از {} به {} ({} جلسه: سختی دل کندن، سختی شروع کار بعدی، میل به ادامه)",
    "{} per hour"
        to "{} در ساعت",
)
