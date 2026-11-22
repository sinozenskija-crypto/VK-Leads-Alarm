package com.vkleads.alarm;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {
    private EditText tokenField, groupsField, intervalField;
    private TextView history;
    private LinearLayout root;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
        }
        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(24,32,24,40);
        root.setBackgroundColor(Color.rgb(247,248,251)); scroll.addView(root); setContentView(scroll);
        TextView title = label("VK LEADS ALARM",23,true); title.setTextColor(Color.rgb(22,73,146)); root.addView(title);
        root.addView(label("Свежие заявки ВК и длительный звуковой сигнал. Тестовая версия без VPS.",14,false));
        root.addView(label("VK API токен (храни только на своём телефоне)",14,true));
        tokenField = input("Токен доступа VK API", getPreferences(MODE_PRIVATE).getString("token",""));
        root.addView(tokenField);
        root.addView(label("Групп для первой проверки (1–235)",14,true));
        groupsField = input("10",String.valueOf(getPreferences(MODE_PRIVATE).getInt("groups",10))); root.addView(groupsField);
        root.addView(label("Пауза между полными проверками, секунд (30–600)",14,true));
        intervalField = input("60",String.valueOf(getPreferences(MODE_PRIVATE).getInt("interval",60))); root.addView(intervalField);
        root.addView(label("При первом успешном обходе старые посты запоминаются, тревоги на них не будет.",12,false));
        button("▶ Запустить мониторинг",()->{ save(); Intent i=new Intent(this,WatchService.class); i.setAction(WatchService.START); if(Build.VERSION.SDK_INT>=26) startForegroundService(i); else startService(i); refresh(); });
        button("🔔 Проверить громкий звонок",()->{ Intent i=new Intent(this,WatchService.class); i.setAction(WatchService.TEST); if(Build.VERSION.SDK_INT>=26) startForegroundService(i); else startService(i); refresh(); });
        button("🔕 Выключить звонок",()->{ Intent i=new Intent(this,WatchService.class); i.setAction(WatchService.SILENCE); startService(i); refresh(); });
        button("■ Остановить мониторинг",()->{ Intent i=new Intent(this,WatchService.class); i.setAction(WatchService.STOP); startService(i); refresh(); });
        root.addView(label("Найденные заявки (нажми на ссылку)",16,true));
        history = label("",13,false); root.addView(history);
        button("↻ Обновить список",this::refresh);
        button("Открыть ВК",()->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://vk.com"))));
        refresh();
    }
    private TextView label(String txt,int sp,boolean strong) {
        TextView v=new TextView(this); v.setText(txt); v.setTextSize(sp); v.setTextColor(Color.rgb(35,40,50));
        v.setPadding(0,9,0,9); if(strong) v.setTypeface(null, Typeface.BOLD); return v;
    }
    private EditText input(String hint,String value){
        EditText v=new EditText(this);v.setSingleLine(true);v.setTextSize(15);v.setHint(hint);v.setText(value);return v;
    }
    private void button(String name,Runnable run){
        Button b=new Button(this);b.setText(name);b.setAllCaps(false);b.setTextSize(15);b.setOnClickListener(v->run.run());root.addView(b);
    }
    private void save(){
        int n=num(groupsField.getText().toString(),10,1,235);
        int seconds=num(intervalField.getText().toString(),60,30,600);
        getPreferences(MODE_PRIVATE).edit().putString("token",tokenField.getText().toString().trim())
            .putInt("groups",n).putInt("interval",seconds).apply();
    }
    private int num(String x,int d,int min,int max){try{return Math.min(max,Math.max(min,Integer.parseInt(x.trim())));}catch(Exception e){return d;}}
    private void refresh(){
        if(history == null) return;
        String value=getSharedPreferences("data",Context.MODE_PRIVATE).getString("history", "Пока нет заявок. Нажми «Проверить громкий звонок» для теста.");
        String status=getSharedPreferences("data",Context.MODE_PRIVATE).getString("status","");
        history.setText(status+"\n\n"+value);
        android.text.util.Linkify.addLinks(history,android.text.util.Linkify.WEB_URLS);
        history.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
    }
    @Override protected void onResume(){super.onResume();if(history!=null)refresh();}
}
