# Jarvis – voice-controlled phone assistant (Android)

"Hey Jarvis, WhatsApp kholo aur Rahul ko 'aa raha hoon' bhejo" → Jarvis sunta hai, Google Gemini (FREE) se sochta hai, aur phone chalata hai.

## APK banane ke 2 tareeke
**A) Android Studio (PC)**: File → Open → `Jarvis` folder → Build → Build APK(s). Phir `app-debug.apk` phone me install karo.
**B) Bina PC ke (GitHub)**: Is folder ko ek naye GitHub repo me upload karo → Actions tab → "Build APK" → run → Artifacts se `Jarvis-apk` download karo.

## Pehli baar setup (app me upar se neeche)
1. FREE Gemini API key lo: aistudio.google.com/apikey → Google se login → Create API key (koi card/paisa nahi). Key app me daalo → Save.
2. Buttons 1–5 dabao aur permissions/switches ON karo (Accessibility sabse zaroori hai).
3. "Start Jarvis" → notification dikhega. Bolo: "Hey Jarvis, ..." 
   Jarvis ke jawab ke baad 8 second tak "Hey Jarvis" dobara bolne ki zaroorat nahi.
Test ke liye app me command type bhi kar sakte ho.

## Kya kar sakta hai
App kholna, call, SMS, alarm/timer, flashlight, volume/brightness, music control, web search/URL,
Settings pages, aur Accessibility se kisi bhi app ki screen padhna/tap/type/scroll/swipe, back/home/recents/screenshot.

## Dhyan rakhne ki baatein
- Hamesha sunne se battery zyada lagti hai; Google "Speech Services" install hona chahiye, internet chahiye.
- Kuch phones (Xiaomi/Oppo/Vivo/Samsung) background apps band kar dete hain → Battery "No restrictions" / Autostart ON karo.
- Recognizer restart par kuch phones beep karte hain.
- API key phone me private storage me rakhi hai; payments/OTP/password Jarvis nahi karega (system prompt me roka gaya hai).
- Banking jaise secure apps screen padhne nahi dete.
- Free tier me rozana limit hoti hai; limit khatam ho to Jarvis bolega 'free limit poori ho gayi'. Model naam app me badla ja sakta hai (default `gemini-3.6-flash`; agar na chale to `gemini-3.5-flash-lite`).
- Free tier me Google aapka data apne products sudharne me use kar sakta hai.
