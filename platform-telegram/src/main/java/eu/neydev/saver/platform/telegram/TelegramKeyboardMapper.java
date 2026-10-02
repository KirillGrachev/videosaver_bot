package eu.neydev.saver.platform.telegram;

import eu.neydev.saver.core.api.InlineKeyboard;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.util.ArrayList;
import java.util.List;

/** Core keyboard -> InlineKeyboardMarkup. Callback_data fit within the 64-byte limit. */
public final class TelegramKeyboardMapper {

    private TelegramKeyboardMapper() {
    }

    public static InlineKeyboardMarkup map(InlineKeyboard keyboard) {

        if (keyboard.isEmpty()) {
            return null;
        }

        List<InlineKeyboardRow> rows = new ArrayList<>(keyboard.rows().size());

        for (List<InlineKeyboard.KeyboardButton> row : keyboard.rows()) {

            List<InlineKeyboardButton> buttons = new ArrayList<>(row.size());

            for (InlineKeyboard.KeyboardButton button : row) {

                InlineKeyboardButton.InlineKeyboardButtonBuilder<?, ?> builder =
                        InlineKeyboardButton.builder().text(button.label());

                if (button.url() != null) {
                    builder.url(button.url());
                } else {
                    builder.callbackData(button.actionId());
                }

                buttons.add(builder.build());

            }

            rows.add(new InlineKeyboardRow(buttons));

        }

        return InlineKeyboardMarkup.builder().keyboard(rows).build();

    }

}

