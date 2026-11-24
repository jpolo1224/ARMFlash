package flash.media {
    import flash.events.ActivityEvent;
    import flash.events.EventDispatcher;
    import flash.events.StatusEvent;
    import flash.events.TimerEvent;
    import flash.utils.ByteArray;
    import flash.utils.Timer;
    import flash.geom.Rectangle;
    import flash.display.BitmapData;

    // ARMFlash: the device camera, from the frontend (see crate::camera). The settings below are
    // kept here as Flash reports them; frames, permission and activity come from the natives.
    public final class Camera extends EventDispatcher {
        private static var cameras:Array = [];

        private var _index:int = 0;
        private var _name:String = "";
        private var _width:int = 160;
        private var _height:int = 120;
        private var _fps:Number = 15;
        private var _bandwidth:int = 16384;
        private var _quality:int = 0;
        private var _keyFrameInterval:int = 15;
        private var _loopback:Boolean = false;
        private var _motionLevel:int = 50;
        private var _motionTimeout:int = 2000;
        private var _started:Boolean = false;
        private var _muted:Boolean = true;
        private var _active:Boolean = false;
        private var _quietSince:Number = 0;
        private var _timer:Timer;

        // A camera by its index as a string ("0"), or the first one. Null when there is none,
        // or no camera with that index.
        public static function getCamera(name:String = null):Camera {
            var all:Array = Camera.names;
            if (all.length == 0) {
                return null;
            }
            var i:int = 0;
            if (name != null) {
                i = int(name);
                if (String(i) != name || i < 0 || i >= all.length) {
                    return null;
                }
            }
            if (cameras[i] == null) {
                var c:Camera = new Camera();
                c._index = i;
                c._name = all[i];
                cameras[i] = c;
                // Flash turns the camera on (and asks) when a game asks for it.
                c.ruffleStart();
            }
            return cameras[i];
        }

        public static native function get names():Array;

        public static function get isSupported():Boolean {
            return Camera.names.length > 0;
        }

        private static native function startCapture(index:int, width:int, height:int, fps:Number):void;
        // 1 allowed, 0 still asking, -1 refused.
        private static native function permission():int;
        private static native function frameWidth():int;
        private static native function frameHeight():int;

        internal function ruffleStart():void {
            if (_started) {
                return;
            }
            _started = true;
            startCapture(_index, _width, _height, _fps);
            _muted = permission() != 1;
            // Status (the permission answer) and activity, checked ten times a second.
            _timer = new Timer(100);
            _timer.addEventListener(TimerEvent.TIMER, tick);
            _timer.start();
        }

        private function tick(e:TimerEvent):void {
            var p:int = permission();
            if (p != 0 && (p != 1) != _muted) {
                _muted = p != 1;
                dispatchEvent(new StatusEvent(StatusEvent.STATUS, false, false, _muted ? "Camera.Muted" : "Camera.Unmuted", "status"));
            }
            // Activity above motionLevel starts it; quiet for motionTimeout ends it.
            var level:Number = activityLevel;
            var now:Number = new Date().time;
            if (level > _motionLevel) {
                _quietSince = now;
                if (!_active) {
                    _active = true;
                    dispatchEvent(new ActivityEvent(ActivityEvent.ACTIVITY, false, false, true));
                }
            } else if (_active && now - _quietSince > _motionTimeout) {
                _active = false;
                dispatchEvent(new ActivityEvent(ActivityEvent.ACTIVITY, false, false, false));
            }
        }

        [API("682")]
        public function copyToByteArray(rect:Rectangle, destination:ByteArray) {
            __ruffle__.stub_method("flash.media.Camera", "copyToByteArray");
        }

        [API("682")]
        public function copyToVector(rect:Rectangle, destination:Vector.<uint>) {
            __ruffle__.stub_method("flash.media.Camera", "copyToVector");
        }

        [API("682")]
        public function drawToBitmapData(destination:BitmapData) {
            __ruffle__.stub_method("flash.media.Camera", "drawToBitmapData");
        }

        public function setKeyFrameInterval(keyFrameInterval:int) {
            _keyFrameInterval = keyFrameInterval;
        }

        public function setLoopback(compress:Boolean = false) {
            _loopback = compress;
        }

        public function setMode(width:int, height:int, fps:Number, favorArea:Boolean = true) {
            if (width > 0) _width = width;
            if (height > 0) _height = height;
            if (fps > 0) _fps = fps;
            if (_started) {
                startCapture(_index, _width, _height, _fps);
            }
        }

        public function setMotionLevel(motionLevel:int, timeout:int = 2000) {
            _motionLevel = Math.max(0, Math.min(100, motionLevel));
            _motionTimeout = Math.max(0, timeout);
        }

        public function setQuality(bandwidth:int, quality:int) {
            _bandwidth = bandwidth;
            _quality = quality;
        }

        public native function get activityLevel():Number;

        public function get bandwidth():int {
            return _bandwidth;
        }

        public native function get currentFPS():Number;

        public function get fps():Number {
            return _fps;
        }

        // The size frames really come in at once they do, as Flash reports the mode it picked.
        public function get height():int {
            var h:int = frameHeight();
            return h > 0 ? h : _height;
        }

        public function get index():int {
            return _index;
        }

        public function get keyFrameInterval():int {
            return _keyFrameInterval;
        }

        public function get loopback():Boolean {
            return _loopback;
        }

        public function get motionLevel():int {
            return _motionLevel;
        }

        public function get motionTimeout():int {
            return _motionTimeout;
        }

        public function get muted():Boolean {
            return _muted;
        }

        public function get name():String {
            return _name;
        }

        public function get quality():int {
            return _quality;
        }

        public function get width():int {
            var w:int = frameWidth();
            return w > 0 ? w : _width;
        }
    }
}
