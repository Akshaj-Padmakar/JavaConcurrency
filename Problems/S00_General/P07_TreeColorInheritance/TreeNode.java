package Problems.S00_General.P07_TreeColorInheritance;

/**
 * assignedColor is null unless this node was given its own explicit color -- that's what lets
 * pushDownFrom() tell "opted out of inheritance" apart from "inherited, keep tracking ancestors."
 * effectiveColor is always non-null once the node exists: what getColor() actually returns.
 */
public class TreeNode {

    private final int id;
    private volatile int parentId; // -1 for the root
    private volatile String assignedColor;
    private volatile String effectiveColor;

    TreeNode(int id, int parentId, String effectiveColor) {
        this.id = id;
        this.parentId = parentId;
        this.assignedColor = null;
        this.effectiveColor = effectiveColor;
    }

    public int getId() {
        return id;
    }

    public int getParentId() {
        return parentId;
    }

    void setParentId(int parentId) {
        this.parentId = parentId;
    }

    public String getAssignedColor() {
        return assignedColor;
    }

    void setAssignedColor(String color) {
        this.assignedColor = color;
    }

    public String getEffectiveColor() {
        return effectiveColor;
    }

    void setEffectiveColor(String color) {
        this.effectiveColor = color;
    }

    public boolean hasExplicitColor() {
        return assignedColor != null;
    }
}
