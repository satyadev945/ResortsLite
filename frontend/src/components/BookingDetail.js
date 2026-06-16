import React, { useState, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import adminService from '../services/adminService';

function BookingDetail() {
  const { id } = useParams();
  const navigate = useNavigate();
  const [booking, setBooking] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    loadBookingDetails();
  }, [id]);

  const loadBookingDetails = async () => {
    setLoading(true);
    setError('');
    try {
      const data = await adminService.getBookingById(id);
      if (data.error) {
        setError(data.error);
      } else {
        setBooking(data);
      }
    } catch (err) {
      setError(err.error || 'Failed to load booking details');
    } finally {
      setLoading(false);
    }
  };

  const handleBack = () => {
    navigate('/dashboard');
  };

  const formatDate = (timestamp) => {
    if (!timestamp) return 'N/A';
    return new Date(timestamp).toLocaleString();
  };

  if (loading) {
    return (
      <div className="booking-detail-container">
        <div className="loading">Loading booking details...</div>
      </div>
    );
  }

  if (error) {
    return (
      <div className="booking-detail-container">
        <button onClick={handleBack} className="back-button">
          Back to Dashboard
        </button>
        <div className="error-message">{error}</div>
      </div>
    );
  }

  if (!booking) {
    return (
      <div className="booking-detail-container">
        <button onClick={handleBack} className="back-button">
          Back to Dashboard
        </button>
        <div className="no-data">Booking not found</div>
      </div>
    );
  }

  return (
    <div className="booking-detail-container">
      <button onClick={handleBack} className="back-button">
        Back to Dashboard
      </button>

      <div className="booking-detail-card">
        <h2>Booking Details</h2>

        <div className="booking-info">
          <div className="info-row">
            <span className="info-label">Booking ID:</span>
            <span className="info-value">{booking.id}</span>
          </div>
          <div className="info-row">
            <span className="info-label">Guest Name:</span>
            <span className="info-value">{booking.guest}</span>
          </div>
          <div className="info-row">
            <span className="info-label">Room Type:</span>
            <span className="info-value">{booking.room}</span>
          </div>
          <div className="info-row">
            <span className="info-label">Check-in Date:</span>
            <span className="info-value">{booking.checkin}</span>
          </div>
          <div className="info-row">
            <span className="info-label">Check-out Date:</span>
            <span className="info-value">{booking.checkout}</span>
          </div>
        </div>

        {booking.ownership_metadata && (
          <div className="metadata-section">
            <h3>Ownership & Permission Metadata</h3>
            <div className="info-row">
              <span className="info-label">Created By:</span>
              <span className="info-value">
                {booking.ownership_metadata.created_by || 'N/A'}
              </span>
            </div>
            <div className="info-row">
              <span className="info-label">Created At:</span>
              <span className="info-value">
                {formatDate(booking.ownership_metadata.created_at)}
              </span>
            </div>
            <div className="info-row">
              <span className="info-label">Access Level:</span>
              <span className="info-value">
                {booking.ownership_metadata.access_level || 'USER'}
              </span>
            </div>
            <div className="info-row">
              <span className="info-label">Role Permissions:</span>
              <span className="info-value">
                {booking.ownership_metadata.role_permissions || 'N/A'}
              </span>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

export default BookingDetail;
