#include <Wire.h>
#include <LSM303.h>

#include <SPI.h>
#include <Adafruit_BLE.h>
#include <Adafruit_BluefruitLE_UART.h>
#include <Adafruit_NeoPixel.h>

//#include <TimerOne.h>
#include <avr/wdt.h>

#define MODES 4

Adafruit_BluefruitLE_UART ble(Serial1, 12); //BLE module
LSM303 lsm; //Accelerometer + Magnetometer
Adafruit_NeoPixel pixels = Adafruit_NeoPixel(4, 10, NEO_GRB + NEO_KHZ800); //NeoPixels

//Global variables
int mode = 0; //Default mode (off)
int activate = 0; //Number of 'heelclicks' registered.
unsigned long schmittTimer = 0; //Prevents repeated heel clicks.
unsigned long refreshTimer = 0;
unsigned long bleTimer = 0;
uint32_t PixelArray[] = {0,0,0,0}; //Caches the current state so that a 'refresh' doesn't always happen
bool pixelChanged = false; //Have the pixels *actually* been changed.
float mouseOffset = 180;
//String serialString;
uint8_t bleString[101];
uint16_t bleIndex = 0;
bool hanged = false;

float GPS[3];
byte TIME[2];

void setup() {
  wdt_disable();
  Mouse.begin();
  Serial.begin(115200);  
  
  //Default calibration values
  lsm.m_min = (LSM303::vector<int16_t>){  -492,   -947,   -402};
  lsm.m_max = (LSM303::vector<int16_t>){  +534,   +182,   +538};
  
  //Try to init BLE
  if (!ble.begin(false)) Serial.println(F("Couldn't find Bluefruit"));
  //Disable command echo from Bluefruit
  ble.echo(false);
  
  //Try to init neopixels
  pixels.begin();

  //Perform a factory reset of BLE to make sure everything is in a known state
  //Serial.println("Performing a factory reset: ");
  //if (!ble.factoryReset()) Serial.println("Couldn't factory reset");
  
  //Change name
  //if (!ble.sendCommandCheckOK(F("AT+GAPDEVNAME=Tom's SmartShoe"))) Serial.println(F("Could not set device name?"));
  
  Serial.println(F("OK!, hardware ready!"));
  
  //ble.println("\n");
  ble.println("!Reset\n");
  /*
  lsm.read();*/
  //Timer1.initialize(5000000);
  
  wdt_enable(WDTO_2S);
  
  Serial.println(F("Enabling LSM"));
  lsm.init();
  lsm.enableDefault();
  
  
}

void setPixel(uint16_t n, uint32_t colour){ //Buffers and caches pixel changes
  //ble.info();
  
  if(PixelArray[n] == colour){ //No change
    return;
  }else{ //Change
    pixels.setPixelColor(n, colour);
    PixelArray[n] = colour;
    pixelChanged = true;
  }
}

float parsefloat(uint8_t *buffer) { //Creates float from 4 memory locations
  float f = ((float *)buffer)[0];
  return f;
}

int calcBearing(float flat1, float flon1, float flat2, float flon2){ 
  //Calculates a bearing from 2 GPS coordinates
  float calc;
  float bear_calc;

  float x = 69.1 * (flat2 - flat1); 
  float y = 69.1 * (flon2 - flon1) * cos(flat1/57.3);

  calc=atan2(y,x);

  bear_calc= degrees(calc);

  if(bear_calc<=1)
    bear_calc=360+bear_calc; 

  return bear_calc;
}

void pointDir(float head, int target){ //Outputs a compass/GPS heading to the pixels
    float brightnesses[] = {0, 0, 0, 0};
    for(int pixel = 0; pixel <= 4; pixel++){ //Loop through all pixels - First one twice, once as 0 and then as 360
      //Angle of this pixel - reversed and -90 as the pixels were wired ccw, and adjusted for target bearing.
      float angle = ((4 - pixel)*90 + target - 90)%360;
      float diff = abs(head - angle); //Difference between this pixel and our heading
      //Calculate 0 <= brightness <= 1
      float brightness = (float)(90 - diff)/(float)90;
      if (brightnesses[pixel%4] <= brightness) brightnesses[pixel%4] = brightness;
    }
    for(int pixel = 0; pixel < 4; pixel++){ //Write out
      setPixel(pixel, pixels.Color(0,(int)(64*brightnesses[pixel]), 0));
    }
}

void loop() {
  wdt_reset();

  lsm.read();
  float head = lsm.heading(); //Get heading from magnetometer
  
  bleTimer = millis();
  while(ble.available() > 0 && millis() - bleTimer < 500){ //Check for incoming BLE data
     char c = (char)ble.read();
     if (c == '!'){ //New command
       bleIndex = 0;
     }
     if(bleIndex < 101){
       bleString[bleIndex] = c;
     }else{
       Serial.println("Overflowed buffer: ");
       for(int j = 0; j < 101; j++){
         Serial.print((char)bleString[j]);
       }
       bleIndex = 0;
     }
     bleIndex++;
  }
  if(bleString[1] == 'L' && bleIndex == 15){ //Parse GPS data
    ble.println("!Lack");
    GPS[0] = parsefloat(bleString+2);
    GPS[1] = parsefloat(bleString+6);
    GPS[2] = parsefloat(bleString+10);
    
    /*Serial.print("GPS Location\t");
    Serial.print("Lat: "); Serial.print(GPS[0], 4);
    Serial.print('\t');
    Serial.print("Lon: "); Serial.print(GPS[1], 4);
    Serial.print('\t');
    Serial.print(GPS[2], 4); Serial.println(" meters");*/
    
    bleIndex = 0; //Reset ble.
  }else if(bleString[1] == 'T' && bleIndex == 11){ //Parse time
    ble.println("!Tack");
    TIME[0] = parsefloat(bleString+2);
    TIME[1] = parsefloat(bleString+6);
    bleIndex = 0;
    
    //Serial.print("TIME: "); Serial.print(TIME[0]); Serial.print(":"); Serial.println(TIME[1]);
  }else if(bleString[1] == 'N' && bleIndex == 7){ //Parse notifications
    //Serial.println(parsefloat(bleString+2));
    //Acknowledge
    ble.println("!Nack");
    //Issue a white 'flare' on the LEDs
    wdt_reset();
    for(int p = 0; p<4; p++){
      if(p>0) pixels.setPixelColor(p-1, pixels.Color(0,0,0));
      pixels.setPixelColor(p, pixels.Color(64, 64, 64));
      pixels.show();
      delay(250);
      
      ble.println("!Nack");
    }
    pixelChanged = true;
    bleIndex = 0;
  }else if(bleString[1] == 'M' && bleIndex == 3){
    mode = bleString[2]-'0'; 
    ble.println("Received Mode, "+(String)mode);
  }
  
  if(mode == 0 && (millis() - schmittTimer) > 500){ //Not active - clear screen
    for(int pixel = 0; pixel < 4; pixel++){
      setPixel(pixel, pixels.Color(0,0,0));
    }
    if(activate > 2){ //3 heel clicks (or more!)
      mode = 1;
      ble.println("!M1");
      activate = 0;
      Serial.println(F("Active!"));
    }
  }else if(mode == 1){ //GPS functionality
    if(GPS[0] == 0 && GPS[1] == 0){ //No fix (yet)
      for(int p = 0; p<4; p++){
        setPixel(p, pixels.Color(64,0,64));//Purple
      }
    }else{ //Fix
      int bearing = calcBearing(GPS[0], GPS[1], 53.376518, -1.494527);
      Serial.print(F("Bearing: ")); Serial.println(bearing);
      pointDir(head, bearing);
    }
  }else if(mode == 2){ //Compass functionality
    pointDir(head, 0);
  }else if(mode == 3){ //Time
    for(int p = 0; p<4; p++){
      setPixel(p, pixels.Color(64*bitRead(round((float)TIME[1]/(float)5), p), 64*bitRead(TIME[0], p), 0));
    }
  }else if(mode == 4){ //Mouse mode
    if((millis() - schmittTimer) < 500) float mouseOffset = head;
    Mouse.move(floor((head - mouseOffset)/80), 0, 0);
  }
  
  //Heel clicks
  if(lsm.a.x < 0 && lsm.a.z > -11000 && (millis() - schmittTimer) > 500){ //Significant 'outward' rotation, lifted off ground - yes, a gyroscope would be better
    if(mode == 0){ //Not active
      if((millis() - schmittTimer) > 5000) activate = 0; //5 second reset.
      if(activate <= 3) activate++; //Increase heelclicks
      
      for(int p = 0; p < activate; p++){ //Write out
        setPixel(p, pixels.Color(64, 0, 0));
      }
    }else{ //Active
      mode++;
      if(mode > MODES){
        mode = 0;
        activate = 2; //Give a 5 second reset.
      }
      ble.println("!M"+(String)mode);
    }
    schmittTimer = millis(); //Reset timer
    Serial.println(F("Heel Click!"));
    //ble.println("Heel Click Over BLE!");
  }
  //Output
  if(pixelChanged || millis() - refreshTimer > 1000){ //Only update if changed, or give a refresh every so often.
    refreshTimer = millis();
    pixels.show();
    pixelChanged = false;
    
    Serial.println(F("What's up!"));
    Serial.println(head);
    //ble.println(head);
  }
}
