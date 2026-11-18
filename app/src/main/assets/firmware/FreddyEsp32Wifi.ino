// Freddy eyes template; adjust GPIO and LED count to your wiring.
// Install Adafruit NeoPixel. For ESP S2/S3 enable USB CDC On Boot.
#include <Arduino.h>
#include <Adafruit_NeoPixel.h>
#define LED_PIN 18
#define NUM_LEDS 12
#define FREDDY_WIFI 1
#if FREDDY_WIFI
#include <WiFi.h>
#include <WiFiUdp.h>
const char* WIFI_AP_NAME = "FreddyEyes";
const char* WIFI_AP_PASSWORD = "freddy-eyes";
WiFiUDP udp;
#endif
Adafruit_NeoPixel leds(NUM_LEDS, LED_PIN, NEO_GRB + NEO_KHZ800);
String incoming, mode;
uint32_t color;
int brightness = 0;
unsigned long lastCommand = 0, lastFrame = 0;
uint32_t namedColor(String name) {
 if(name == "RED") return leds.Color(255,0,0);
 if(name == "BLUE") return leds.Color(0,0,255);
 if(name == "YELLOW") return leds.Color(255,100,0);
 if(name == "PINK") return leds.Color(255,40,160);
 if(name == "ORANGE") return leds.Color(255,40,0);
 return leds.Color(150,150,150);
}
void command(String cmd) {
 cmd.trim(); if(!cmd.length()) return; lastCommand=millis();
 if(cmd=="OFF") {brightness=0;mode="";}
 else if(cmd.startsWith("B") && cmd.length()>1 && isDigit(cmd[1])) {brightness=constrain(cmd.substring(1).toInt(),0,255);mode="";}
 else if(cmd.startsWith("C")) color=namedColor(cmd.substring(1));
 else if(cmd=="DISCO"||cmd=="GLITCH"||cmd=="MUSIC") {mode=cmd;brightness=100;}
 else {color=namedColor(cmd);brightness=100;mode="";}
}
void receive(char c) {
 if(c=='\n') {command(incoming);incoming="";}
 else if(c!='\r') {if(incoming.length()<96) incoming+=c;else incoming="";}
}
void setup() {
 Serial.begin(115200);leds.begin();leds.clear();leds.show();color=namedColor("WHITE");
#if FREDDY_WIFI
 WiFi.softAP(WIFI_AP_NAME,WIFI_AP_PASSWORD);udp.begin(18888);
#endif
}
void loop() {
 while(Serial.available()) receive((char)Serial.read());
#if FREDDY_WIFI
 if(udp.parsePacket()) while(udp.available()) receive((char)udp.read());
#endif
 unsigned long now=millis();if(now-lastCommand>1500) brightness=0;
 if(now-lastFrame<20) return;lastFrame=now;
 uint32_t frame=color;int level=brightness;
 if(mode=="DISCO") frame=namedColor(((now/400)%3)==0?"RED":((now/400)%3)==1?"BLUE":"YELLOW");
 if(mode=="GLITCH" && random(4)==0) level=0;
 if(mode=="MUSIC") {frame=leds.Color(150,150,255);if((now/250)%2) level=0;}
 for(int i=0;i<NUM_LEDS;i++) leds.setPixelColor(i,(((frame>>16)&255)*level)/255,(((frame>>8)&255)*level)/255,((frame&255)*level)/255);
 leds.show();
}
