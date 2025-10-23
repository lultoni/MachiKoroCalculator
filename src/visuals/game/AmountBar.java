package visuals.game;

import javax.swing.*;
import java.awt.*;

public class AmountBar extends JPanel {

    private int filled_amount, maximum_amount;

    public AmountBar(int filled_amount, int maximum_amount) {
        this.filled_amount = filled_amount;
        this.maximum_amount = maximum_amount;

        setLayout(new GridLayout(1, 0));
        setBorder(BorderFactory.createLineBorder(Color.WHITE));

        update();
    }

    public void change_fill(int new_filled_amount) {
        filled_amount = new_filled_amount;
        update();
    }

    public void change_max(int new_max_amount) {
        maximum_amount = new_max_amount;
        update();
    }

    public void update() {
        removeAll();
        for (int i = 0; i < filled_amount; i++) {
            add(get_full_box(i + 1));
        }
        for (int i = 0; i < maximum_amount - filled_amount; i++) {
            add(get_empty_box(-1)); // i + filled_amount + 1
        }
        revalidate();
        repaint();
    }

    private JLabel get_empty_box(int number) {
        JLabel empty_box = new JLabel(number < 0 ? "" : String.valueOf(number));
        empty_box.setHorizontalAlignment(SwingConstants.CENTER);
        empty_box.setOpaque(true);
        return empty_box;
    }

    private JLabel get_full_box(int number) {
        JLabel full_box = new JLabel(String.valueOf(number));
        full_box.setOpaque(true);
        full_box.setBackground(new Color(14, 28, 39));
        full_box.setForeground(Color.WHITE);
        full_box.setHorizontalAlignment(SwingConstants.CENTER);
        return full_box;
    }

}
