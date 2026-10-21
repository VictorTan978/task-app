/** Models matching the trash schemas in `contracts/openapi.yaml` (0.16.0) exactly. */
import { Task } from '../tasks/tasks.models';

/** A `Task` plus when and by whom it was trashed. */
export interface TrashItem extends Task {
  /** UTC instant with `Z` suffix. */
  deletedAt: string;
  /** Who trashed it (current display name), or null when that account was deleted. */
  deletedBy: { displayName: string } | null;
  /** `deletedAt` + 30 days: when it is deleted for good (within an hour after). */
  purgeAt: string;
}

/** `GET /api/trash`: newest deleted first, at most 50 per page. */
export interface TrashPage {
  items: TrashItem[];
  /** Trashed tasks matching `list` on all pages, counted when the page was produced. */
  total: number;
  /** Opaque; null on the last page. */
  nextCursor: string | null;
}

/** `POST /api/trash/empty`. */
export interface EmptyTrashResult {
  deleted: number;
}
