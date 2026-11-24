package flash.media {
    import __ruffle__.stub_method;
    import __ruffle__.stub_getter;

    import flash.display.DisplayObject
    import flash.net.NetStream

    [Ruffle(InstanceAllocator)]
    public class Video extends DisplayObject {
        private var _deblocking:int;
        private var _smoothing:Boolean;
        private var _videoWidth:int;
        private var _videoHeight:int;

        public function Video(width:int = 320, height:int = 240) {
            this.init(width, height);
        }

        private native function init(width:int, height:int);

        public function get deblocking():int {
            return this._deblocking;
        }

        public function set deblocking(value:int):void {
            this._deblocking = value;
        }

        public function get smoothing():Boolean {
            return this._smoothing;
        }

        public function set smoothing(value:Boolean):void {
            this._smoothing = value;
        }

        // The size of the frames shown (camera or stream), 0 before the first one.
        public native function get videoWidth():int;

        public native function get videoHeight():int;

        public native function attachNetStream(netStream:NetStream):void;

        // ARMFlash: the device camera (null shows nothing).
        public function attachCamera(camera:Camera):void {
            if (camera != null) {
                camera.ruffleStart();
            }
            this.attachCameraNative(camera != null);
        }

        private native function attachCameraNative(on:Boolean):void;

        public function clear():void {
            stub_method("flash.media.Video", "clear");
        }
    }
}
