import { useState, type FormEvent } from 'react';
import type { Booking, BookingRequest, Room, User } from './types';

export interface BookingDraft {
  booking?: Booking;
  roomId?: number;
  start: Date;
  end: Date;
}

interface Props {
  draft: BookingDraft;
  rooms: Room[];
  currentUser: User;
  onSave: (request: BookingRequest, id?: number) => Promise<void>;
  onDelete: (id: number) => Promise<void>;
  onClose: () => void;
}

// <input type="datetime-local"> works with local wall-clock time without a zone suffix
function toLocalInput(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

export default function BookingModal({ draft, rooms, currentUser, onSave, onDelete, onClose }: Props) {
  const existing = draft.booking;
  // Only the organizer may change a booking; everyone else gets a read-only view
  const readOnly = !!existing && existing.organizerId !== currentUser.id;

  const [roomId, setRoomId] = useState<number>(existing?.roomId ?? draft.roomId ?? rooms[0]?.id);
  const [title, setTitle] = useState(existing?.title ?? '');
  const [description, setDescription] = useState(existing?.description ?? '');
  const [start, setStart] = useState(toLocalInput(draft.start));
  const [end, setEnd] = useState(toLocalInput(draft.end));
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const run = async (action: () => Promise<void>) => {
    setBusy(true);
    setError(null);
    try {
      await action();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  const handleSubmit = (e: FormEvent) => {
    e.preventDefault();
    const startDate = new Date(start);
    const endDate = new Date(end);
    if (endDate <= startDate) {
      setError('End time must be after start time');
      return;
    }
    void run(() =>
      onSave(
        {
          roomId,
          title: title.trim(),
          description: description.trim() || null,
          startTime: startDate.toISOString(),
          endTime: endDate.toISOString(),
        },
        existing?.id,
      ),
    );
  };

  const handleDelete = () => {
    if (existing && window.confirm(`Delete booking "${existing.title}"?`)) {
      void run(() => onDelete(existing.id));
    }
  };

  const selectedRoom = rooms.find((r) => r.id === roomId);
  const organizer = existing
    ? `${existing.organizerName}${existing.organizerEmail ? ` (${existing.organizerEmail})` : ''}`
    : `${currentUser.fullName} (${currentUser.email})`;

  return (
    <div className="modal-backdrop" onMouseDown={onClose}>
      <form className="modal" onMouseDown={(e) => e.stopPropagation()} onSubmit={handleSubmit}>
        <header className="modal-header">
          <h2>{readOnly ? 'Booking details' : existing ? 'Edit booking' : 'New booking'}</h2>
          <button type="button" className="icon-button" onClick={onClose} aria-label="Close">
            ×
          </button>
        </header>

        <fieldset disabled={readOnly || busy}>
          <label>
            Room
            <select value={roomId} onChange={(e) => setRoomId(Number(e.target.value))} required>
              {rooms.map((room) => (
                <option key={room.id} value={room.id}>
                  {room.name} · {room.capacity} seats
                </option>
              ))}
            </select>
            {selectedRoom?.location && <small className="hint">{selectedRoom.location}</small>}
          </label>

          <label>
            Title
            <input value={title} onChange={(e) => setTitle(e.target.value)} maxLength={200} required autoFocus={!readOnly} />
          </label>

          <div className="field">
            <span>Organizer</span>
            <div className="static-value">{organizer}</div>
          </div>

          <div className="row">
            <label>
              Start
              <input type="datetime-local" value={start} onChange={(e) => setStart(e.target.value)} required />
            </label>
            <label>
              End
              <input type="datetime-local" value={end} onChange={(e) => setEnd(e.target.value)} required />
            </label>
          </div>

          <label>
            Description
            <textarea value={description} onChange={(e) => setDescription(e.target.value)} maxLength={2000} rows={3} />
          </label>
        </fieldset>

        {readOnly && <div className="info">Only the organizer can change or delete this booking.</div>}
        {error && <div className="error">{error}</div>}

        <footer className="modal-footer">
          {existing && !readOnly && (
            <button type="button" className="danger" onClick={handleDelete} disabled={busy}>
              Delete
            </button>
          )}
          <span className="spacer" />
          <button type="button" onClick={onClose} disabled={busy}>
            {readOnly ? 'Close' : 'Cancel'}
          </button>
          {!readOnly && (
            <button type="submit" className="primary" disabled={busy}>
              {existing ? 'Save changes' : 'Book room'}
            </button>
          )}
        </footer>
      </form>
    </div>
  );
}
