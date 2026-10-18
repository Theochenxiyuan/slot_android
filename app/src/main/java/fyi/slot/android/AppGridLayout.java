package fyi.slot.android;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/** Fixed-column grid whose rows take the height of their tallest cell. */
final class AppGridLayout extends ViewGroup {
    private int columns = 6, horizontalSpacing, verticalSpacing;

    AppGridLayout(Context context) { super(context); }

    void setSpacing(int columnCount, int horizontal, int vertical) {
        columns = Math.max(1, columnCount); horizontalSpacing = horizontal; verticalSpacing = vertical;
        requestLayout();
    }

    int columns() { return columns; }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int cellWidth = cellWidth(width);
        int height = getPaddingTop() + getPaddingBottom();
        int count = getChildCount();
        for (int row = 0; row * columns < count; row++) {
            int rowHeight = 0;
            for (int i = row * columns; i < Math.min(count, (row + 1) * columns); i++) {
                View child = getChildAt(i);
                child.measure(MeasureSpec.makeMeasureSpec(cellWidth, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                rowHeight = Math.max(rowHeight, child.getMeasuredHeight());
            }
            for (int i = row * columns; i < Math.min(count, (row + 1) * columns); i++) {
                getChildAt(i).measure(MeasureSpec.makeMeasureSpec(cellWidth, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(rowHeight, MeasureSpec.EXACTLY));
            }
            height += rowHeight + (row > 0 ? verticalSpacing : 0);
        }
        setMeasuredDimension(width, height);
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int cellWidth = cellWidth(r - l);
        int y = getPaddingTop();
        int count = getChildCount();
        for (int row = 0; row * columns < count; row++) {
            int rowHeight = 0;
            for (int i = row * columns; i < Math.min(count, (row + 1) * columns); i++) {
                View child = getChildAt(i);
                int x = getPaddingLeft() + (i % columns) * (cellWidth + horizontalSpacing);
                child.layout(x, y, x + cellWidth, y + child.getMeasuredHeight());
                rowHeight = Math.max(rowHeight, child.getMeasuredHeight());
            }
            y += rowHeight + verticalSpacing;
        }
    }

    private int cellWidth(int width) {
        int available = width - getPaddingLeft() - getPaddingRight() - horizontalSpacing * (columns - 1);
        return Math.max(0, available / columns);
    }
}
