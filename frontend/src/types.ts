export interface Room {
  id: number;
  name: string;
  location: string | null;
  capacity: number;
  description: string | null;
}

export interface Booking {
  id: number;
  roomId: number;
  roomName: string;
  title: string;
  organizerId: number | null;
  organizerName: string;
  organizerEmail: string | null;
  description: string | null;
  startTime: string;
  endTime: string;
  createdAt: string;
  updatedAt: string;
}

export interface BookingRequest {
  roomId: number;
  title: string;
  description: string | null;
  startTime: string;
  endTime: string;
}

export interface User {
  id: number;
  email: string;
  fullName: string;
}

export interface Session {
  accessToken: string;
  expiresAt: string;
  user: User;
}

export interface ChatMessage {
  role: 'user' | 'assistant';
  text: string;
}
