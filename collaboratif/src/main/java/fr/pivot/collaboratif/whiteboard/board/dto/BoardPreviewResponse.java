package fr.pivot.collaboratif.whiteboard.board.dto;

import fr.pivot.collaboratif.whiteboard.canvas.Card;
import fr.pivot.collaboratif.whiteboard.canvas.Frame;

import java.util.List;

/**
 * Lightweight geometry-only wire representation of a board's canvas, returned by
 * {@code GET /whiteboard/boards/{boardId}/preview}.
 *
 * <p>Deliberately excludes {@link Card#getContent()} (card text, base64 image data-URLs, table
 * cell data…) and every other non-geometric field — this endpoint exists so the frontend
 * board-list can render a mini-thumbnail of each board without downloading full card content
 * (which may include large base64-encoded images). Any caller with board access may request a
 * preview, including a {@code VIEWER} — access is enforced the same way as
 * {@code GET /whiteboard/boards/{boardId}}, via {@code BoardService#requireAccessibleBoard}.
 *
 * @param cards  the board's cards, geometry and colour only, ordered by layer then creation time
 * @param frames the board's frames, geometry and colour only, ordered by layer then creation time
 */
public record BoardPreviewResponse(List<PreviewCard> cards, List<PreviewFrame> frames) {

    /**
     * Lightweight geometry-only wire representation of a {@link Card}, deliberately omitting
     * {@code content} (the card's text/base64-image payload).
     *
     * @param type   the {@link fr.pivot.collaboratif.whiteboard.canvas.CardType} name
     * @param posX   the X position
     * @param posY   the Y position
     * @param width  the width
     * @param height the height
     * @param color  the hex colour
     */
    public record PreviewCard(
            String type, double posX, double posY, double width, double height, String color) {

        /**
         * Builds a {@link PreviewCard} from a persisted {@link Card}, keeping only its typed
         * discriminant, geometry, and colour.
         *
         * @param card the persisted card
         * @return a new {@link PreviewCard}
         */
        public static PreviewCard from(final Card card) {
            return new PreviewCard(
                    card.getType().name(),
                    card.getPosX(),
                    card.getPosY(),
                    card.getWidth(),
                    card.getHeight(),
                    card.getColor());
        }
    }

    /**
     * Lightweight geometry-only wire representation of a {@link Frame}.
     *
     * @param posX   the X position
     * @param posY   the Y position
     * @param width  the width
     * @param height the height
     * @param color  the hex colour
     */
    public record PreviewFrame(double posX, double posY, double width, double height, String color) {

        /**
         * Builds a {@link PreviewFrame} from a persisted {@link Frame}, keeping only its
         * geometry and colour.
         *
         * @param frame the persisted frame
         * @return a new {@link PreviewFrame}
         */
        public static PreviewFrame from(final Frame frame) {
            return new PreviewFrame(
                    frame.getPosX(), frame.getPosY(), frame.getWidth(), frame.getHeight(), frame.getColor());
        }
    }
}
