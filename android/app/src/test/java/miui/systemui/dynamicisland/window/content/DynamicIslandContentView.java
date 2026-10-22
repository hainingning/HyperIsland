package miui.systemui.dynamicisland.window.content;

/** Shape verified against this phone's native suspend state machine. */
public final class DynamicIslandContentView {
    public Object currentIslandData;
    public boolean failRead;
    public Object getCurrentIslandData() {
        if (failRead) throw new IllegalStateException("test lookup failure");
        return currentIslandData;
    }
    public static final class updateBigIslandView$1 {
        public Object this$0;
        public Object L$0;
        public Object L$1;
        public int label;
        public updateBigIslandView$1(DynamicIslandContentView owner, Object data) {
            this$0=owner;L$0=owner;L$1=data;label=Integer.MIN_VALUE|1;
        }
    }
}
