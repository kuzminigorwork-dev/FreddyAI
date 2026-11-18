// Full controller release 2026-10-23. USB CDC telemetry: 115200 baud.
// Corrected final screenshot 16:40: SWG = physical SWC (GPIO5); SWD = GPIO4. SWA/SWB do not select colors.
// Arduino-ESP32 3.x + Adafruit NeoPixel >=1.15.5. No ESP32Servo dependency.
#include <Arduino.h>
#include <Adafruit_NeoPixel.h>
#include <esp_arduino_version.h>

#define RC_PWM 0
#define RC_IBUS 1
#define RC_DIRECT 2
// PWM = receiver channel outputs, IBUS = one receiver iBUS wire,
// DIRECT = physical switches/potentiometers wired directly to the board.
#ifndef FREDDY_INPUT_MODE
#if CONFIG_IDF_TARGET_ESP32C3
#define FREDDY_INPUT_MODE RC_IBUS
#else
#define FREDDY_INPUT_MODE RC_PWM
#endif
#endif

// These are GPIO numbers, never receiver channel numbers.
// Default profiles avoid flash/USB GPIOs; changing boards requires matching wiring.
#if CONFIG_IDF_TARGET_ESP32S2
// Updated user wiring, 2026-10-23. Only one servo is installed.
#define PIN_SWA 1
#define PIN_SWB 2
#define PIN_SWC 5
#define PIN_SWD 4
#define PIN_VrA 3
#define PIN_VrB 6
#define PIN_LED 8
#define PIN_SERVO1 7
#define PIN_SERVO2 -1
#define PIN_LED_HEAD 9
#define PIN_LED_MOUTH 10
#define PIN_IBUS 12
#elif CONFIG_IDF_TARGET_ESP32S3
#define PIN_SWA 4
#define PIN_SWB 5
#define PIN_SWC 6
#define PIN_SWD 7
#define PIN_VrA 9
#define PIN_VrB 10
#define PIN_LED 8
#define PIN_SERVO1 16
#define PIN_SERVO2 17
#define PIN_LED_HEAD 18
#define PIN_LED_MOUTH 21
#define PIN_IBUS 12
#elif CONFIG_IDF_TARGET_ESP32C3
#if FREDDY_INPUT_MODE != RC_IBUS
#error "C3 profile uses iBUS; provide a reviewed GPIO profile before changing input mode."
#endif
#define PIN_SWA -1
#define PIN_SWB -1
#define PIN_SWC -1
#define PIN_SWD -1
#define PIN_VrA -1
#define PIN_VrB -1
#define PIN_LED 6
#define PIN_SERVO1 4
#define PIN_SERVO2 5
#define PIN_LED_HEAD 7
#define PIN_LED_MOUTH 10
#define PIN_IBUS 20
#else
#error "Supported profiles: ESP32-S2, ESP32-S3, ESP32-C3."
#endif

// Optional physical nose button: normally-open pushbutton GPIO11 <-> GND.
// ESP32-C3 GPIO12 belongs to flash, so this optional input is disabled there.
#if CONFIG_IDF_TARGET_ESP32S2 || CONFIG_IDF_TARGET_ESP32S3
#define PIN_NOSE_BUTTON 11
#else
#define PIN_NOSE_BUTTON -1
#endif

#define NUM_LEDS 12
#define DEFAULT_BRIGHTNESS 100
#define USE_BRIGHTNESS_POT 1 // Receiver CH10 (VrB) -> GPIO6 on S2 Mini.
#define MIN_BRIGHTNESS 0
#define MAX_BRIGHTNESS 255
#define MIN_SPEED 0.3f
#define MAX_SPEED 2.5f
#define PATTERN_TIME 1500u
#define DISCO_PATTERN_TIME 8000u
#define BACKGROUND_GLITCH 0 // Glitch is activated only by the SWC pattern.
#define HEAD_LED_DELAY 1000u
#define HEAD_LED_FADE 500u
#define SERVO_FULL_TRAVEL_MS 2000.0f
#define SERVO_MIN_US 500
#define SERVO_MAX_US 2500
#define PHONE_TIMEOUT_MS 3000u
#define RECEIVER_TIMEOUT_US 250000u
// iBUS channel numbers from the PDF, in SWA, SWB, SWC, SWD, VrA, VrB order.
const uint8_t IBUS_CHANNELS[6]={7,8,5,6,9,10};
const int INPUT_PINS[6]={PIN_SWA,PIN_SWB,PIN_SWC,PIN_SWD,PIN_VrA,PIN_VrB};

struct Color {
 uint8_t r,g,b;
 constexpr Color(uint8_t red=0,uint8_t green=0,uint8_t blue=0):r(red),g(green),b(blue){}
 constexpr bool equals(Color other)const{return r==other.r&&g==other.g&&b==other.b;}
};
const Color COLOR_OFF(0,0,0),COLOR_WHITE(150,150,150),COLOR_YELLOW(255,100,0),COLOR_RED(255,0,0),COLOR_BLUE(0,0,255);
const Color DISCO_COLORS[]={Color(255,0,0),Color(0,255,0),Color(0,0,255),Color(255,255,0),Color(255,0,255),Color(0,255,255),Color(255,100,0),Color(100,255,100),Color(100,100,255),Color(255,100,255),Color(255,255,100),Color(100,255,255)};
enum Mode {NORMAL,BLUE,ORANGE,MUSIC_BOX,DISCO,GLITCH};
constexpr bool phoneLeaseFresh(uint32_t now,uint32_t stamp){return uint32_t(now-stamp)<PHONE_TIMEOUT_MS||uint32_t(stamp-now)<=20u;}
static_assert(phoneLeaseFresh(1000,1001),"A command received during the current millisecond must not blank one frame");
static_assert(!phoneLeaseFresh(5000,1000),"Disconnected phone must still time out");
static_assert(phoneLeaseFresh(5,0xfffffffeu),"Lease must handle millis rollover");

// Six transitions mean THREE complete there-and-back cycles.
// A held initial position does not consume the pattern time window.
template<unsigned N> struct SequenceTracker {
 int positions[N]; unsigned step=0; int last=0; uint32_t started=0;
 constexpr void reset(){step=0;last=0;started=0;}
 constexpr bool feed(int position,uint32_t now,uint32_t window=PATTERN_TIME){
  if(position==last)return false;
  last=position;
  if(step>1&&uint32_t(now-started)>window)step=0;
  if(step==0){step=position==positions[0]?1:0;return false;}
  if(position!=positions[step]){step=position==positions[0]?1:0;return false;}
  if(step==1)started=now;
  if(++step==N){step=1;return true;}
  return false;
 }
};
constexpr bool testThreeCycles(){SequenceTracker<7> p{{1,2,1,2,1,2,1}};int seq[]={1,2,1,2,1,2,1};for(int i=0;i<7;i++){bool fired=p.feed(seq[i],10000+i*200);if(fired!=(i==6))return false;}return true;}
constexpr bool testHeld(){SequenceTracker<7> p{{1,2,1,2,1,2,1}};for(int i=0;i<20;i++)if(p.feed(1,i*1000))return false;int seq[]={2,1,2,1,2,1};bool fired=false;for(int i=0;i<6;i++)fired=p.feed(seq[i],20000+i*100);return fired;}
constexpr bool testDisco(){SequenceTracker<9> p{{1,2,3,2,1,2,3,2,1}};int seq[]={1,2,3,2,1,2,3,2,1};for(int i=0;i<9;i++)if(p.feed(seq[i],i*200,DISCO_PATTERN_TIME)!=(i==8))return false;return true;}
constexpr bool testWrongDisco(){SequenceTracker<9> p{{1,2,3,2,1,2,3,2,1}};for(int i=0;i<30;i++)if(p.feed(i%2?3:2,i*150,DISCO_PATTERN_TIME))return false;return true;}
constexpr bool testTimeout(){SequenceTracker<7> p{{1,2,1,2,1,2,1}};for(int i=0;i<7;i++)if(p.feed(i%2?2:1,i*1000))return false;return true;}
static_assert(testThreeCycles(),"Require three COMPLETE cycles");
static_assert(testHeld(),"A held switch cannot add edges or expire the initial position");
static_assert(testDisco(),"Match full disco path including position 1");
static_assert(testWrongDisco(),"Old 2/3 disco pattern must not trigger");
static_assert(testTimeout(),"Reject slow patterns");
SequenceTracker<7> glitchPattern{{2,3,2,3,2,3,2}};
SequenceTracker<6> voicePattern{{1,2,1,2,1,2}};
constexpr bool testVoicePattern(){SequenceTracker<6> p{{1,2,1,2,1,2}};int seq[]={1,2,1,2,1,2};for(int i=0;i<6;i++)if(p.feed(seq[i],i*200)!=(i==5))return false;return true;}
constexpr bool testSingleVoiceSwitch(){SequenceTracker<6> p{{1,2,1,2,1,2}};p.feed(1,0);return !p.feed(2,100)&&!p.feed(2,200)&&!p.feed(2,1400);}
static_assert(testVoicePattern(),"Voice effects require 1-2-1-2-1-2 within 1500ms");
static_assert(testSingleVoiceSwitch(),"One SWD switch must never start music");
SequenceTracker<9> discoPattern{{1,2,3,2,1,2,3,2,1}};
Adafruit_NeoPixel leds(NUM_LEDS,PIN_LED,NEO_GRB+NEO_KHZ800);
HardwareSerial receiverBus(1);
int swgPosition=1,swdPosition=1;
Mode mode=NORMAL;Color baseColor=COLOR_OFF,currentColor=COLOR_OFF,phoneColor=COLOR_WHITE;
bool receiverValid=false,aiEnabled=false,outputsReady=false;bool signalPresent[6]={false};uint32_t ledTestStart=0;bool ledTestActive=false;
int brightness=DEFAULT_BRIGHTNESS,phoneBrightness=0;
float speedFactor=1,currentAngle1=0,currentAngle2=0;
uint32_t lastRender=0,lastServo=0,lastPhone=0,lastTelemetry=0,modeTime=0;
bool phoneHasCommand=false,musicBoxState=false,glitchDark=false,headYellow=false;
uint32_t headStart=0,effectTime=0,effectWait=0;
float headLevel=0;int discoIndex=0;Color discoColor=COLOR_OFF;
uint8_t glitchMask[NUM_LEDS]={0};
uint8_t musicFlashBrightness=255;
struct EffectGuard {int g,d;uint32_t start;bool sourceD=false;constexpr bool changed(int nextG,int nextD,uint32_t now){bool other=sourceD?nextG!=g:nextD!=d;bool source=sourceD?nextD!=d:nextG!=g;g=nextG;d=nextD;return other||(uint32_t(now-start)>=2000&&source);}};
EffectGuard effectGuard{1,2,0,false};
constexpr bool testEffectGrace(){EffectGuard guard{1,2,1000,true};if(guard.changed(1,1,1200)||guard.changed(1,2,1700)||guard.changed(1,1,2999)||guard.changed(1,1,3000))return false;if(!guard.changed(1,2,3001))return false;EffectGuard other{1,2,1000,true};if(!other.changed(2,2,1001))return false;EffectGuard color{2,2,1000,false};if(color.changed(3,2,1500)||!color.changed(3,1,1501))return false;EffectGuard wrap{1,2,0xfffffff0u,true};return !wrap.changed(1,1,100)&&wrap.changed(1,2,2010);}
static_assert(testEffectGrace(),"Other switch cancels immediately; initiating switch only cancels on a new edge after 2 seconds");
volatile uint32_t pulseRise[6]={0},pulseWidth[6]={0},pulseSeen[6]={0};
uint16_t channel[6]={0};
int stableSwitch[3]={1,1,1},candidateSwitch[3]={1,1,1};uint32_t switchSince[3]={0};

void ARDUINO_ISR_ATTR receiverEdge(void* argument){int index=(int)(intptr_t)argument;uint32_t now=micros();if(digitalRead(INPUT_PINS[index]))pulseRise[index]=now;else{uint32_t width=now-pulseRise[index];if(width>=800&&width<=2200){pulseWidth[index]=width;pulseSeen[index]=now;}}}
void readReceiver(){
#if FREDDY_INPUT_MODE == RC_PWM
 uint32_t widths[6],seenAt[6];noInterrupts();for(int i=0;i<6;i++){widths[i]=pulseWidth[i];seenAt[i]=pulseSeen[i];}interrupts();uint32_t now=micros();for(int i=0;i<6;i++){uint32_t seen=seenAt[i],width=widths[i];signalPresent[i]=(i!=5||USE_BRIGHTNESS_POT)&&seen&&uint32_t(now-seen)<=RECEIVER_TIMEOUT_US;channel[i]=signalPresent[i]?constrain((int)width,1000,2000):1500;}receiverValid=signalPresent[3];
#elif FREDDY_INPUT_MODE == RC_IBUS
 static uint8_t packet[32];static int used=0;static uint32_t lastFrame=0;int budget=128;
 while(receiverBus.available()&&budget--){uint8_t value=receiverBus.read();if(used==0&&value!=0x20)continue;packet[used++]=value;if(used==2&&packet[1]!=0x40){used=0;continue;}if(used==32){used=0;uint16_t sum=0xffff;for(int i=0;i<30;i++)sum-=packet[i];if(sum!=(uint16_t)(packet[30]|packet[31]<<8))continue;bool valid=true;for(int i=0;i<6;i++){int at=2+2*(IBUS_CHANNELS[i]-1);uint16_t width=packet[at]|packet[at+1]<<8;if(width<800||width>2200)valid=false;channel[i]=constrain((int)width,1000,2000);}if(valid)lastFrame=micros();}}
 receiverValid=lastFrame&&uint32_t(micros()-lastFrame)<=RECEIVER_TIMEOUT_US;for(int i=0;i<6;i++)signalPresent[i]=receiverValid;
#else
 receiverValid=true;for(int i=0;i<6;i++)signalPresent[i]=true;channel[0]=digitalRead(PIN_SWA)?1000:2000;channel[1]=digitalRead(PIN_SWB)?1000:2000;channel[3]=digitalRead(PIN_SWD)?1000:2000;channel[2]=map(analogRead(PIN_SWC),0,4095,1000,2000);channel[4]=map(analogRead(PIN_VrA),0,4095,1000,2000);channel[5]=map(analogRead(PIN_VrB),0,4095,1000,2000);
#endif
}
int switchPosition(int index,int rcIndex,uint32_t now){int proposed=channel[rcIndex]<1500?1:2;if(proposed!=candidateSwitch[index]){candidateSwitch[index]=proposed;switchSince[index]=now;}if(now-switchSince[index]>=25)stableSwitch[index]=proposed;return stableSwitch[index];}
Color normalColor(int g,int d){if(d!=2)return COLOR_OFF;return g==1?COLOR_WHITE:g==2?COLOR_YELLOW:COLOR_RED;}
uint8_t blendChannel(uint8_t from,uint8_t to,float amount){if(from==to)return to;int delta=(int)to-from,step=max(1,(int)(abs(delta)*amount));return (uint8_t)((int)from+(delta>0?min(step,delta):-min(step,-delta)));}
Color blendColor(Color from,Color to,float amount){return Color(blendChannel(from.r,to.r,amount),blendChannel(from.g,to.g,amount),blendChannel(from.b,to.b,amount));}
void activate(Mode requested,uint32_t now){effectGuard={swgPosition,swdPosition,now,requested==BLUE||requested==MUSIC_BOX};mode=mode==requested?NORMAL:requested;modeTime=effectTime=now;effectWait=0;musicBoxState=mode==MUSIC_BOX;glitchDark=false;discoIndex=0;discoColor=DISCO_COLORS[0];for(int i=0;i<NUM_LEDS;i++)glitchMask[i]=0;digitalWrite(PIN_LED_MOUTH,LOW);}

String phoneLine;bool droppingLine=false;
int phoneLoading=0;
bool extendedPhoneCommand(const String& line){
 if(line.startsWith("E")){int r,g,b,level;char extra;if(sscanf(line.c_str(),"E%d,%d,%d,%d%c",&r,&g,&b,&level,&extra)==4&&r>=0&&r<=255&&g>=0&&g<=255&&b>=0&&b<=255&&level>=0&&level<=255){phoneColor=Color(r,g,b);phoneBrightness=level;phoneHasCommand=true;lastPhone=millis();}return true;}
 if(line.startsWith("L")){bool digits=line.length()>1;for(unsigned i=1;i<line.length();i++)if(!isDigit(line[i]))digits=false;if(digits){phoneLoading=constrain(line.substring(1).toInt(),0,255);phoneHasCommand=true;lastPhone=millis();}return true;}
 return false;
}
void phoneCommand(String line){line.trim();if(extendedPhoneCommand(line))return;if(line.startsWith("RGB")){int r,g,b;char extra;if(sscanf(line.c_str(),"RGB%d,%d,%d%c",&r,&g,&b,&extra)==3&&r>=0&&r<=255&&g>=0&&g<=255&&b>=0&&b<=255){phoneColor=Color(r,g,b);phoneHasCommand=true;lastPhone=millis();}return;}if(line=="INFO"){lastTelemetry=0;return;}if(line=="TEST"){ledTestStart=millis();ledTestActive=true;return;}if(line=="PING"){lastPhone=millis();return;}if(line=="OFF"){phoneBrightness=0;phoneHasCommand=true;lastPhone=millis();}else if(line.startsWith("B")&&line.length()>1){bool digits=true;for(unsigned i=1;i<line.length();i++)if(!isDigit(line[i]))digits=false;if(digits){phoneBrightness=constrain(line.substring(1).toInt(),0,255);phoneHasCommand=true;lastPhone=millis();}}
 else if(line.startsWith("C")){String color=line.substring(1);if(color=="WHITE")phoneColor=COLOR_WHITE;else if(color=="YELLOW")phoneColor=COLOR_YELLOW;else if(color=="RED")phoneColor=COLOR_RED;else if(color=="BLUE")phoneColor=COLOR_BLUE;else if(color=="ORANGE")phoneColor=Color(255,40,0);else if(color=="PINK")phoneColor=Color(255,40,160);}}
void readPhone(){int budget=128;while(Serial.available()&&budget--){char c=Serial.read();if(c=='\n'){if(!droppingLine)phoneCommand(phoneLine);phoneLine="";droppingLine=false;}else if(c!='\r'&&!droppingLine){if(phoneLine.length()<95)phoneLine+=c;else{phoneLine="";droppingLine=true;}}}}
void sendTelemetry(uint32_t now){static bool sent=false,oldAI=false,oldValid=false;static int oldState=-1;int state=int(mode)*16+swgPosition*4+swdPosition;if(sent&&oldState==state&&oldAI==aiEnabled&&oldValid==receiverValid&&now-lastTelemetry<500)return;sent=true;oldState=state;oldAI=aiEnabled;oldValid=receiverValid;lastTelemetry=now;
 Serial.print("{\"freddy\":1,\"fw\":\"1.9.9-rx9\",\"pins\":[");for(int i=0;i<6;i++){if(i)Serial.print(',');Serial.print(INPUT_PINS[i]);}Serial.print("],\"nosePin\":");Serial.print(PIN_NOSE_BUTTON);Serial.print(",\"valid\":");Serial.print(receiverValid?"true":"false");Serial.print(",\"ai\":");Serial.print(aiEnabled?"true":"false");Serial.print(",\"manualValid\":");Serial.print(signalPresent[2]&&signalPresent[3]?"true":"false");Serial.print(",\"swc\":");Serial.print(swgPosition);Serial.print(",\"swd\":");Serial.print(swdPosition);Serial.print(",\"rgb\":[");uint32_t pixel=leds.getPixelColor(0);Serial.print((pixel>>16)&255);Serial.print(',');Serial.print((pixel>>8)&255);Serial.print(',');Serial.print(pixel&255);Serial.print("],\"nose\":");Serial.print(PIN_NOSE_BUTTON>=0&&digitalRead(PIN_NOSE_BUTTON)==LOW?"true":"false");Serial.print(",\"mode\":");Serial.print((int)mode);Serial.print(",\"pwmReady\":");Serial.print(outputsReady?"true":"false");Serial.print(",\"seen\":[");for(int i=0;i<6;i++){if(i)Serial.print(',');Serial.print(signalPresent[i]?1:0);}Serial.print("],\"pwm\":[");for(int i=0;i<6;i++){if(i)Serial.print(',');Serial.print(signalPresent[i]?channel[i]:0);}Serial.print("],\"phone\":");Serial.print(phoneHasCommand&&phoneLeaseFresh(now,lastPhone)?"true":"false");Serial.println("}");
}

void writeHead(int duty){if(!outputsReady)return;ledcWrite(PIN_LED_HEAD,constrain(duty,0,255));}
void writeServo(int pin,float angle){if(pin<0||!outputsReady)return;float us=SERVO_MIN_US+(SERVO_MAX_US-SERVO_MIN_US)*constrain(angle,0.0f,180.0f)/180.0f;ledcWrite(pin,(uint32_t)(us*16383.0f/20000.0f));}
void updateServos(uint32_t now){if(!outputsReady||now-lastServo<20)return;float dt=min((uint32_t)50,now-lastServo);lastServo=now;float target1=signalPresent[2]?(channel[2]<1300?0:channel[2]<1700?90:180):currentAngle1,target2=signalPresent[3]?(aiEnabled?180:0):currentAngle2;
 float step=180.0f*dt/SERVO_FULL_TRAVEL_MS;float d1=target1-currentAngle1,d2=target2-currentAngle2;currentAngle1+=constrain(d1,-step,step);currentAngle2+=constrain(d2,-step,step);writeServo(PIN_SERVO1,currentAngle1);writeServo(PIN_SERVO2,currentAngle2);
}
void render(uint32_t now){
 if(now-lastRender<20)return;
 uint32_t dt=min((uint32_t)100,now-lastRender);lastRender=now;
 bool manualValid=signalPresent[2]&&signalPresent[3];
 bool phoneControl=phoneHasCommand&&phoneLeaseFresh(now,lastPhone);
 Color output=COLOR_OFF;
 if(phoneControl){output=Color((uint16_t)phoneColor.r*phoneBrightness/255,(uint16_t)phoneColor.g*phoneBrightness/255,(uint16_t)phoneColor.b*phoneBrightness/255);}
 else if(manualValid&&(swdPosition==2||mode!=NORMAL)){
  if(mode==NORMAL||mode==BLUE||mode==ORANGE||mode==GLITCH){
   Color target=mode==BLUE?COLOR_BLUE:mode==ORANGE?Color(255,40,0):baseColor;
   currentColor=blendColor(currentColor,target,min(1.0f,.1f*speedFactor));output=currentColor;
  }else if(mode==MUSIC_BOX){
   if(effectWait==0){musicBoxState=true;effectTime=now;effectWait=random(200,701);musicFlashBrightness=random(200,256);}
   if(uint32_t(now-effectTime)>=effectWait){effectTime=now;musicBoxState=!musicBoxState;effectWait=musicBoxState?random(200,701):random(100,501);if(musicBoxState)musicFlashBrightness=random(200,256);}
   output=musicBoxState?Color(COLOR_WHITE.r*musicFlashBrightness/255,COLOR_WHITE.g*musicFlashBrightness/255,COLOR_WHITE.b*musicFlashBrightness/255):COLOR_OFF;
  }else if(mode==DISCO){
   if(uint32_t(now-effectTime)>=(uint32_t)(80/speedFactor)){effectTime=now;discoIndex=(discoIndex+1)%12;}
   discoColor=blendColor(discoColor,DISCO_COLORS[discoIndex],min(1.0f,.05f*speedFactor));output=discoColor;
  }
 }else{currentColor=COLOR_OFF;}
 // Glitch overlay runs only after the SWC 2/3 pattern, never in other modes.
 static uint32_t glitchTime=0,glitchWait=1800;static bool glitchOn=false;
 bool glitchAllowed=mode==GLITCH&&manualValid&&!phoneControl&&!output.equals(COLOR_OFF);
 if(!glitchAllowed){glitchOn=false;glitchTime=now;glitchWait=1800;}
 else if(uint32_t(now-glitchTime)>=glitchWait){
  glitchTime=now;glitchOn=!glitchOn;glitchWait=glitchOn?random(30,121):random(1200,4001);
  for(int i=0;i<NUM_LEDS;i++)glitchMask[i]=0;
  if(glitchOn){int remaining=random(2,4);while(remaining){int i=random(NUM_LEDS);if(!glitchMask[i]){glitchMask[i]=1;remaining--;}}}
 }
 if(ledTestActive){uint32_t elapsed=now-ledTestStart;if(elapsed>=1800)ledTestActive=false;else output=elapsed<600?Color(100,0,0):elapsed<1200?Color(0,100,0):Color(0,0,100);}
 leds.setBrightness(phoneControl?(signalPresent[5]?brightness:255):brightness);
 for(int i=0;i<NUM_LEDS;i++){Color c=!ledTestActive&&glitchAllowed&&glitchOn&&glitchMask[i]?COLOR_OFF:output;if(phoneControl&&phoneLoading>0&&!ledTestActive)c=i==(int)((now/85)%NUM_LEDS)?Color(150*phoneLoading/255,150*phoneLoading/255,150*phoneLoading/255):COLOR_OFF;leds.setPixelColor(i,c.r,c.g,c.b);}leds.show();
 // Wait for the actual end colour, including the phone's smooth transition.
 bool yellow=phoneControl?(phoneBrightness>0&&phoneLoading==0&&phoneColor.equals(COLOR_YELLOW)):
   (manualValid&&swdPosition==2&&mode==NORMAL&&swgPosition==2&&currentColor.equals(COLOR_YELLOW));
 headYellow=yellow;
 float headTarget=yellow?(signalPresent[5]?brightness:255):0;
 float headStep=255.0f*dt/HEAD_LED_FADE;
 headLevel+=constrain(headTarget-headLevel,-headStep,headStep);
 writeHead((int)headLevel);
 digitalWrite(PIN_LED_MOUTH,manualValid&&!phoneControl&&mode==MUSIC_BOX&&musicBoxState?HIGH:LOW);
}
bool noseStable=false,noseCandidate=false;uint32_t noseChanged=0;
void readNoseButton(uint32_t now){
 if(PIN_NOSE_BUTTON<0)return;
 bool pressed=digitalRead(PIN_NOSE_BUTTON)==LOW;
 if(pressed!=noseCandidate){noseCandidate=pressed;noseChanged=now;}
 if(pressed!=noseStable&&uint32_t(now-noseChanged)>=35){noseStable=pressed;if(pressed)Serial.println("{\"freddy\":1,\"event\":\"nose\"}");}
}
void setup(){
 if(PIN_NOSE_BUTTON>=0)pinMode(PIN_NOSE_BUTTON,INPUT_PULLUP);
 Serial.begin(115200);analogReadResolution(12);pinMode(PIN_LED_MOUTH,OUTPUT);digitalWrite(PIN_LED_MOUTH,LOW);leds.begin();leds.clear();leds.show();
 outputsReady=ledcAttachChannel(PIN_SERVO1,50,14,0)&&(PIN_SERVO2<0||ledcAttachChannel(PIN_SERVO2,50,14,1))&&ledcAttachChannel(PIN_LED_HEAD,5000,8,2);if(!outputsReady){Serial.println("{\"freddy\":1,\"valid\":false,\"ai\":false,\"error\":\"PWM setup failed\"}");}writeServo(PIN_SERVO1,0);writeServo(PIN_SERVO2,0);writeHead(0);
#if FREDDY_INPUT_MODE == RC_PWM
 for(int i=0;i<6;i++){if(i==5&&!USE_BRIGHTNESS_POT)continue;pinMode(INPUT_PINS[i],INPUT_PULLDOWN);attachInterruptArg(INPUT_PINS[i],receiverEdge,(void*)(intptr_t)i,CHANGE);}
#elif FREDDY_INPUT_MODE == RC_IBUS
 receiverBus.begin(115200,SERIAL_8N1,PIN_IBUS,-1);
#else
 pinMode(PIN_SWA,INPUT_PULLUP);pinMode(PIN_SWB,INPUT_PULLUP);pinMode(PIN_SWD,INPUT_PULLUP);
#endif
 lastServo=millis();
}
int readThreePosition(uint32_t now){
 static int stable=1,candidate=1;static uint32_t since=0;
 int proposed=channel[2]<1300?1:channel[2]>1700?3:2;
 if(proposed!=candidate){candidate=proposed;since=now;}
 if(uint32_t(now-since)>=25)stable=proposed;return stable;
}
void resetPatterns(){glitchPattern.reset();voicePattern.reset();discoPattern.reset();}
void loop(){
 readNoseButton(millis());readPhone();readReceiver();uint32_t now=millis();
 swgPosition=readThreePosition(now);swdPosition=switchPosition(2,3,now);
 aiEnabled=receiverValid&&swdPosition==2;
 brightness=USE_BRIGHTNESS_POT&&signalPresent[5]?map(channel[5],1000,2000,MIN_BRIGHTNESS,MAX_BRIGHTNESS):DEFAULT_BRIGHTNESS;
 speedFactor=signalPresent[4]?MIN_SPEED+(MAX_SPEED-MIN_SPEED)*(channel[4]-1000)/1000.0f:1;
 bool valid=signalPresent[2]&&signalPresent[3];
 baseColor=valid?normalColor(swgPosition,swdPosition):COLOR_OFF;
 static bool previousAI=false;static int previousG=0,previousD=0;
 if(aiEnabled!=previousAI){previousAI=aiEnabled;}
 if(!valid){mode=NORMAL;resetPatterns();previousG=previousD=0;}
 else if(mode!=NORMAL){
  if(effectGuard.changed(swgPosition,swdPosition,now)){mode=NORMAL;resetPatterns();previousG=swgPosition;previousD=swdPosition;}
 }
 else{
  // SWD pattern is shared by BLUE (SWC=3) and MUSIC BOX (SWC=1).
  // Exact 1-2-1-2-1-2 path; only track a new pattern in normal mode.
  if(previousD!=swdPosition){glitchPattern.reset();discoPattern.reset();}
  if(previousG!=swgPosition)voicePattern.reset();
  bool voice=false,glitch=false,disco=false;
  if(swgPosition==1||swgPosition==3)voice=voicePattern.feed(swdPosition,now);else voicePattern.reset();
  if(swdPosition==2){glitch=glitchPattern.feed(swgPosition,now);disco=discoPattern.feed(swgPosition,now,DISCO_PATTERN_TIME);}
  else{glitchPattern.reset();discoPattern.reset();}
  if(disco)activate(DISCO,now);else if(voice)activate(swgPosition==3?BLUE:MUSIC_BOX,now);else if(glitch)activate(GLITCH,now);
  previousG=swgPosition;previousD=swdPosition;
 }
 updateServos(now);render(now);sendTelemetry(now);delay(1);
}








