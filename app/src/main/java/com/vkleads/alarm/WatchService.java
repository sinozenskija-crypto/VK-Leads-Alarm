package com.vkleads.alarm;

import android.app.*;
import android.content.*;
import android.media.*;
import android.net.Uri;
import android.os.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import org.json.*;

/** Read-only public VK wall monitor. Requires a valid VK API access token.
 * No auto-commenting, account creation or restriction bypass.
 */
public class WatchService extends Service {
    static final String START="start", STOP="stop", TEST="test", SILENCE="silence";
    private static final int SERVICE_ID=201, ALERT_ID=202;
    private static final String SERVICE_CHANNEL="monitor", ALERT_CHANNEL="leads_alarm";
    private static final Pattern URL=Pattern.compile("https?://(?:vk\\.com|vk\\.ru)/([A-Za-z0-9_.-]+)");
    private static final Pattern ASK=Pattern.compile("нужен|нужна|нужны|нужно|ищу|ищем|кто может|кто сможет|кто сделает|посоветуйте|подскажите|помогите|требуется|сломал|протекает|течет|потек|не работает|не включается|сколько стоит|срочно",Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE);
    private static final Pattern SELL=Pattern.compile("предлагаю услуги|оказываем услуги|наши услуги|выполняем работы|звоните|свободные вакансии|вахта|оклад|резюме",Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE);
    private static final String[][] CATEGORIES={
        {"Сантехник", "сантехник|унитаз|бачок|труб|водопровод|канализац|раковк|смесител|кран|теч|протеч|засор|душ|сифон|сантех"},
        {"Электрик", "электрик|электропровод|розетк|выключател|светильник|люстр|проводк|заменить провод|нет света"},
        {"Сборка мебели", "сборк|собрать|соберет|соберёт|разобрать|шкаф|комод|кровать|кухонный гарнитур"},
        {"Мастер на час", "мастер на час|муж на час|повесить|прикрутить|просверлит|мелкий ремонт|карниз"},
        {"Окна", "стеклопакет|окн|пвх|подоконник|уплотнител|балконн"},
        {"Холодильник", "холодильник|морозильник|не морозит"},
        {"Стиральная машина", "стиралк|стиральн|не отжимает"},
        {"Посудомоечная машина", "посудомойк|посудомоечн"},
        {"Кофемашина", "кофемашин|кофевар"},
        {"Телевизор", "телевизор|плазм|экран телевизора"},
        {"Компьютер", "компьютер|ноутбук|системн.*блок|пк не включ"}
    };
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private volatile boolean monitoring=false;
    private MediaPlayer player;
    private ToneGenerator backupTone;
    private boolean alarmOn=false;
    private String currentUrl="https://vk.com";
    private final Runnable fallbackBeep=new Runnable(){@Override public void run(){
        if(alarmOn && backupTone!=null){ backupTone.startTone(ToneGenerator.TONE_PROP_BEEP2,750); handler.postDelayed(this,1100); }
    }};

    @Override public void onCreate(){super.onCreate();makeChannels();}
    private void makeChannels(){
        NotificationManager mgr=getSystemService(NotificationManager.class);
        NotificationChannel monitor=new NotificationChannel(SERVICE_CHANNEL,"Мониторинг ВК",NotificationManager.IMPORTANCE_LOW);
        monitor.setDescription("Постоянный мониторинг новых постов");mgr.createNotificationChannel(monitor);
        NotificationChannel alerts=new NotificationChannel(ALERT_CHANNEL,"Новые заявки · тревога",NotificationManager.IMPORTANCE_HIGH);
        alerts.enableVibration(true);alerts.setVibrationPattern(new long[]{0,600,300,600});
        alerts.setDescription("Важные новые заявки, отключение звука вручную");mgr.createNotificationChannel(alerts);
    }
    private PendingIntent appIntent(){
        Intent i=new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this,3,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }
    private PendingIntent action(String action,int code){
        Intent i=new Intent(this,WatchService.class);i.setAction(action);
        return PendingIntent.getService(this,code,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }
    private Notification baseNotif(String text){
        return new Notification.Builder(this,SERVICE_CHANNEL)
            .setContentTitle("VK Leads Alarm").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_popup_reminder).setContentIntent(appIntent())
            .setOngoing(true).setCategory(Notification.CATEGORY_SERVICE)
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause,"Стоп",action(STOP,4)).build())
            .build();
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        String act=intent==null?START:intent.getAction();
        if(act==null)act=START;
        if(STOP.equals(act)){
            monitoring=false;silence();writeStatus("Мониторинг остановлен");stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();return START_NOT_STICKY;
        }
        if(SILENCE.equals(act)){
            silence();writeStatus(monitoring?"Мониторинг включён · звук отключён":"Звук отключён");
            if(!monitoring){stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}return START_NOT_STICKY;
        }
        startForeground(SERVICE_ID,baseNotif("Мониторинг запускается…"));
        if(TEST.equals(act)){
            alert("ТЕСТ", "Проверка громкого звонка", "https://vk.com");
            writeStatus("Тестовая тревога запущена. Нажми «Выключить звонок»");
            return START_STICKY;
        }
        if(!monitoring){monitoring=true;executor.submit(this::monitorLoop);}
        return START_STICKY;
    }
    private void writeStatus(String status){getSharedPreferences("data",MODE_PRIVATE).edit().putString("status",status).apply();}
    private List<String[]> loadGroups(int limit){
        List<String[]> result=new ArrayList<>();
        try(BufferedReader br=new BufferedReader(new InputStreamReader(getAssets().open("groups.csv"),StandardCharsets.UTF_8))){
            String line;while((line=br.readLine())!=null){
                // Groups are ordered by priority in the bundled CSV. Select first N regardless of legacy enabled flag.
                Matcher m=URL.matcher(line);if(!m.find())continue;
                String city=line.split(",",3)[1].trim();
                result.add(new String[]{city,m.group(1)});
                if(result.size()>=limit)break;
            }
        }catch(Exception ex){writeStatus("Не удалось прочитать группы: "+ex.getMessage());}
        return result;
    }
    private String category(String text){
        if(text.length()<12||!ASK.matcher(text).find()||SELL.matcher(text).find())return null;
        for(String[] c:CATEGORIES){if(Pattern.compile(c[1],Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE).matcher(text).find())return c[0];}
        return null;
    }
    private JSONObject callApi(String domain,String token) throws Exception{
        URL url=new URL("https://api.vk.com/method/wall.get");
        HttpURLConnection con=(HttpURLConnection)url.openConnection();
        con.setRequestMethod("POST");con.setConnectTimeout(10000);con.setReadTimeout(10000);con.setDoOutput(true);
        con.setRequestProperty("Content-Type","application/x-www-form-urlencoded; charset=UTF-8");
        String form="domain="+URLEncoder.encode(domain,"UTF-8")+"&count=10&v=5.199&access_token="+URLEncoder.encode(token,"UTF-8");
        try(OutputStream out=con.getOutputStream()){out.write(form.getBytes(StandardCharsets.UTF_8));}
        try(InputStream in=con.getInputStream()){
            ByteArrayOutputStream buffer=new ByteArrayOutputStream();byte[] bytes=new byte[4096];int n;
            while((n=in.read(bytes))>=0){buffer.write(bytes,0,n);if(buffer.size()>800000)break;}
            return new JSONObject(buffer.toString("UTF-8"));
        }finally{con.disconnect();}
    }
    private void monitorLoop(){
        android.content.SharedPreferences pref=getSharedPreferences("MainActivity",MODE_PRIVATE);
        String token=pref.getString("token", "").trim();
        if(token.isEmpty()){
            writeStatus("Не указан токен VK API. Для мониторинга нужен действующий доступ к wall.get.");
            monitoring=false;stopSelf();return;
        }
        int limit=pref.getInt("groups",10),interval=pref.getInt("interval",60);
        List<String[]> groups=loadGroups(limit);
        if(groups.isEmpty()){monitoring=false;writeStatus("Нет активных групп в базе");stopSelf();return;}
        android.content.SharedPreferences db=getSharedPreferences("seen",MODE_PRIVATE);
        LinkedHashSet<String> seen=new LinkedHashSet<>(db.getStringSet("ids",Collections.emptySet()));
        Set<String> boot=new HashSet<>(db.getStringSet("boot",Collections.emptySet()));
        writeStatus("Мониторинг включён · групп: "+groups.size());
        while(monitoring){
            long started=System.currentTimeMillis();int ok=0,errors=0,found=0;String lastError="";
            for(String[] group:groups){
                if(!monitoring)break;
                String city=group[0],domain=group[1];
                try{
                    JSONObject data=callApi(domain,token);
                    if(data.has("error")){
                        JSONObject e=data.getJSONObject("error");
                        errors++;lastError=domain+": "+e.optInt("error_code")+" "+e.optString("error_msg");
                        if(e.optInt("error_code")==5){monitoring=false;lastError="Ошибка авторизации VK API. Проверь токен";}
                        continue;
                    }
                    JSONArray posts=data.getJSONObject("response").getJSONArray("items");ok++;
                    boolean first=!boot.contains(domain);
                    for(int p=posts.length()-1;p>=0;p--){
                        JSONObject post=posts.optJSONObject(p);if(post==null)continue;
                        long owner=post.optLong("owner_id"),id=post.optLong("id");
                        if(owner==0||id==0)continue;
                        String key=owner+"_"+id;
                        if(!seen.add(key))continue;
                        if(first)continue; // First successful scan is a baseline, not an alert flood.
                        String content=post.optString("text","");
                        String type=category(content);
                        if(type!=null){
                            String link="https://vk.com/wall"+key;
                            String shortText=content.replaceAll("\\s+"," ").trim();
                            if(shortText.length()>240)shortText=shortText.substring(0,240)+"…";
                            appendHistory(type+" · "+city+"\n"+shortText+"\n"+link+"\n");
                            alert(type+" · "+city,shortText,link);found++;
                        }
                    }
                    if(first)boot.add(domain);
                }catch(Exception ex){errors++;lastError=domain+": "+ex.getClass().getSimpleName()+" "+ex.getMessage();}
                try{Thread.sleep(850);}catch(InterruptedException e){Thread.currentThread().interrupt();monitoring=false;break;}
            }
            while(seen.size()>5000){Iterator<String> iter=seen.iterator();iter.next();iter.remove();}
            db.edit().putStringSet("ids",new LinkedHashSet<>(seen)).putStringSet("boot",new HashSet<>(boot)).apply();
            if(monitoring){
                writeStatus("Мониторинг включён · проверено "+ok+"/"+groups.size()+" · ошибок "+errors+" · новых "+found+"\n"+(errors>0?lastError:""));
                long elapsed=System.currentTimeMillis()-started;
                long ms=Math.max(1000,interval*1000L-elapsed);
                try{Thread.sleep(ms);}catch(InterruptedException ex){Thread.currentThread().interrupt();monitoring=false;break;}
            }
        }
    }
    private synchronized void appendHistory(String line){
        android.content.SharedPreferences p=getSharedPreferences("data",MODE_PRIVATE);
        String old=p.getString("history","");
        String value=line+"\n"+old;
        if(value.length()>25000)value=value.substring(0,25000);
        p.edit().putString("history",value).apply();
    }
    private synchronized void alert(String title,String body,String link){
        currentUrl=link;alarmOn=true;
        if(player==null&&backupTone==null){
            try{
                Uri ring=RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
                if(ring==null)ring=RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
                player=new MediaPlayer();
                player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
                player.setDataSource(this,ring);player.setLooping(true);player.prepare();player.start();
            }catch(Exception ex){
                if(player!=null){player.release();player=null;}
                backupTone=new ToneGenerator(AudioManager.STREAM_ALARM,100);
                handler.post(fallbackBeep);
            }
        }
        Intent open=new Intent(Intent.ACTION_VIEW,Uri.parse(link));
        PendingIntent linkIntent=PendingIntent.getActivity(this,75,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b=new Notification.Builder(this,ALERT_CHANNEL).setContentTitle("🚨 "+title)
            .setContentText(body).setStyle(new Notification.BigTextStyle().bigText(body))
            .setSmallIcon(android.R.drawable.ic_dialog_alert).setCategory(Notification.CATEGORY_ALARM)
            .setPriority(Notification.PRIORITY_MAX).setAutoCancel(false)
            .setContentIntent(linkIntent)
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_view,"Открыть пост",linkIntent).build())
            .addAction(new Notification.Action.Builder(android.R.drawable.ic_lock_silent_mode,"Выключить звонок",action(SILENCE,74)).build());
        getSystemService(NotificationManager.class).notify(ALERT_ID,b.build());
    }
    private synchronized void silence(){
        alarmOn=false;handler.removeCallbacks(fallbackBeep);
        if(player!=null){try{player.stop();}catch(Exception ignored){}player.release();player=null;}
        if(backupTone!=null){backupTone.release();backupTone=null;}
        getSystemService(NotificationManager.class).cancel(ALERT_ID);
    }
    @Override public void onDestroy(){monitoring=false;silence();executor.shutdownNow();super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
