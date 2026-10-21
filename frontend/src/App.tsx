import { useCallback, useEffect, useMemo, useState } from 'react';
import FullCalendar from '@fullcalendar/react';
import dayGridPlugin from '@fullcalendar/daygrid';
import timeGridPlugin from '@fullcalendar/timegrid';
import listPlugin from '@fullcalendar/list';
import interactionPlugin, { type EventResizeDoneArg } from '@fullcalendar/interaction';
import type { DateSelectArg, DatesSetArg, EventClickArg, EventDropArg, EventInput } from '@fullcalendar/core';
import { api } from './api';
import AuthPage from './AuthPage';
import BookingModal, { type BookingDraft } from './BookingModal';
import ChatPanel from './ChatPanel';
import { getSession, SESSION_EXPIRED_EVENT, setSession } from './session';
import type { Booking, BookingRequest, Room, Session, User } from './types';

const ROOM_COLORS = ['#2563eb', '#16a34a', '#d97706', '#9333ea', '#dc2626', '#0891b2', '#db2777', '#65a30d'];

function toRequest(booking: Booking, start: Date, end: Date): BookingRequest {
  return {
    roomId: booking.roomId,
    title: booking.title,
    description: booking.description,
    startTime: start.toISOString(),
    endTime: end.toISOString(),
  };
}

export default function App() {
  const [session, setSessionState] = useState<Session | null>(getSession);
  const [notice, setNotice] = useState<string | null>(null);

  useEffect(() => {
    const onExpired = () => {
      setSessionState(null);
      setNotice('Your session has expired, please sign in again');
    };
    window.addEventListener(SESSION_EXPIRED_EVENT, onExpired);
    return () => window.removeEventListener(SESSION_EXPIRED_EVENT, onExpired);
  }, []);

  const signIn = (next: Session) => {
    setSession(next);
    setSessionState(next);
    setNotice(null);
  };

  const signOut = () => {
    setSession(null);
    setSessionState(null);
  };

  if (!session) {
    return <AuthPage onAuthenticated={signIn} notice={notice} />;
  }
  return <CalendarPage user={session.user} onSignOut={signOut} />;
}

function CalendarPage({ user, onSignOut }: { user: User; onSignOut: () => void }) {
  const [rooms, setRooms] = useState<Room[]>([]);
  const [bookings, setBookings] = useState<Booking[]>([]);
  const [range, setRange] = useState<{ start: Date; end: Date } | null>(null);
  const [hiddenRooms, setHiddenRooms] = useState<Set<number>>(new Set());
  const [onlyMine, setOnlyMine] = useState(false);
  const [draft, setDraft] = useState<BookingDraft | null>(null);
  const [toast, setToast] = useState<string | null>(null);

  const colorOf = useCallback(
    (roomId: number) => ROOM_COLORS[Math.max(0, rooms.findIndex((r) => r.id === roomId)) % ROOM_COLORS.length],
    [rooms],
  );

  const showError = (e: unknown) => setToast(e instanceof Error ? e.message : String(e));

  useEffect(() => {
    api.getRooms().then(setRooms).catch(showError);
  }, []);

  const loadBookings = useCallback(async () => {
    if (!range) return;
    try {
      setBookings(await api.getBookings(range.start, range.end));
    } catch (e) {
      showError(e);
    }
  }, [range]);

  useEffect(() => {
    void loadBookings();
  }, [loadBookings]);

  useEffect(() => {
    if (!toast) return;
    const timer = setTimeout(() => setToast(null), 5000);
    return () => clearTimeout(timer);
  }, [toast]);

  const events = useMemo<EventInput[]>(
    () =>
      bookings
        .filter((b) => !hiddenRooms.has(b.roomId) && (!onlyMine || b.organizerId === user.id))
        .map((b) => {
          const mine = b.organizerId === user.id;
          return {
            id: String(b.id),
            title: `${b.title} · ${b.roomName} · ${mine ? 'you' : b.organizerName}`,
            start: b.startTime,
            end: b.endTime,
            backgroundColor: colorOf(b.roomId),
            borderColor: colorOf(b.roomId),
            // Only the organizer can drag / resize their booking
            editable: mine,
            classNames: mine ? ['own-booking'] : ['foreign-booking'],
            extendedProps: { booking: b },
          };
        }),
    [bookings, hiddenRooms, onlyMine, colorOf, user.id],
  );

  const firstVisibleRoom = () => rooms.find((r) => !hiddenRooms.has(r.id))?.id;

  const handleDatesSet = (arg: DatesSetArg) => {
    setRange((prev) =>
      prev && prev.start.getTime() === arg.start.getTime() && prev.end.getTime() === arg.end.getTime()
        ? prev
        : { start: arg.start, end: arg.end },
    );
  };

  const handleSelect = (arg: DateSelectArg) => {
    arg.view.calendar.unselect();
    // In month view a selection spans whole days, default to a one-hour slot instead
    const start = arg.allDay ? new Date(arg.start.getTime() + 9 * 3600_000) : arg.start;
    const end = arg.allDay ? new Date(start.getTime() + 3600_000) : arg.end;
    setDraft({ start, end, roomId: firstVisibleRoom() });
  };

  const handleEventClick = (arg: EventClickArg) => {
    const booking = arg.event.extendedProps.booking as Booking;
    setDraft({ booking, start: new Date(booking.startTime), end: new Date(booking.endTime) });
  };

  // Drag & drop and resize both reschedule the booking in place
  const handleEventChange = async (arg: EventDropArg | EventResizeDoneArg) => {
    const booking = arg.event.extendedProps.booking as Booking;
    if (!arg.event.start || !arg.event.end) {
      arg.revert();
      return;
    }
    try {
      await api.updateBooking(booking.id, toRequest(booking, arg.event.start, arg.event.end));
      await loadBookings();
    } catch (e) {
      arg.revert();
      showError(e);
    }
  };

  const handleSave = async (request: BookingRequest, id?: number) => {
    if (id) {
      await api.updateBooking(id, request);
    } else {
      await api.createBooking(request);
    }
    setDraft(null);
    await loadBookings();
  };

  const handleDelete = async (id: number) => {
    await api.deleteBooking(id);
    setDraft(null);
    await loadBookings();
  };

  const toggleRoom = (id: number) =>
    setHiddenRooms((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });

  const newBooking = () => {
    const start = new Date();
    start.setMinutes(start.getMinutes() < 30 ? 30 : 60, 0, 0);
    setDraft({ start, end: new Date(start.getTime() + 3600_000), roomId: firstVisibleRoom() });
  };

  return (
    <div className="layout">
      <aside className="sidebar">
        <h1>Room Booking</h1>

        <div className="user-card">
          <div>
            <strong>{user.fullName}</strong>
            <small>{user.email}</small>
          </div>
          <button type="button" onClick={onSignOut}>
            Sign out
          </button>
        </div>

        <button className="primary block" onClick={newBooking} disabled={rooms.length === 0}>
          + New booking
        </button>

        <label className="room">
          <input type="checkbox" checked={onlyMine} onChange={(e) => setOnlyMine(e.target.checked)} />
          <span>Only my bookings</span>
        </label>

        <h3>Rooms</h3>
        <ul className="rooms">
          {rooms.map((room) => (
            <li key={room.id}>
              <label className="room">
                <input type="checkbox" checked={!hiddenRooms.has(room.id)} onChange={() => toggleRoom(room.id)} />
                <span className="swatch" style={{ background: colorOf(room.id) }} />
                <span>
                  <strong>{room.name}</strong>
                  <small>
                    {room.capacity} seats{room.location ? ` · ${room.location}` : ''}
                  </small>
                </span>
              </label>
            </li>
          ))}
        </ul>
        <p className="tip">
          Select a time range in the calendar to book. Drag or resize your own bookings to move them. Bookings of other
          people are shown faded and are read-only.
        </p>
      </aside>

      <main className="calendar">
        <FullCalendar
          plugins={[dayGridPlugin, timeGridPlugin, listPlugin, interactionPlugin]}
          initialView="timeGridWeek"
          headerToolbar={{
            left: 'prev,next today',
            center: 'title',
            right: 'dayGridMonth,timeGridWeek,timeGridDay,listWeek',
          }}
          firstDay={1}
          height="100%"
          nowIndicator
          allDaySlot={false}
          slotMinTime="07:00:00"
          slotMaxTime="21:00:00"
          scrollTime="08:00:00"
          businessHours={{ daysOfWeek: [1, 2, 3, 4, 5], startTime: '09:00', endTime: '18:00' }}
          selectable
          selectMirror
          editable
          eventOverlap
          events={events}
          datesSet={handleDatesSet}
          select={handleSelect}
          eventClick={handleEventClick}
          eventDrop={handleEventChange}
          eventResize={handleEventChange}
        />
      </main>

      {draft && (
        <BookingModal
          draft={draft}
          rooms={rooms}
          currentUser={user}
          onSave={handleSave}
          onDelete={handleDelete}
          onClose={() => setDraft(null)}
        />
      )}

      <ChatPanel user={user} />

      {toast && (
        <div className="toast" role="alert" onClick={() => setToast(null)}>
          {toast}
        </div>
      )}
    </div>
  );
}
