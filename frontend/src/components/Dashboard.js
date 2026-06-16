import React, { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import authService from '../services/authService';
import adminService from '../services/adminService';

function Dashboard() {
  const [bookings, setBookings] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [user, setUser] = useState(null);
  const [currentPage, setCurrentPage] = useState(1);
  const itemsPerPage = 10;
  const navigate = useNavigate();

  useEffect(() => {
    loadUserInfo();
    loadBookings();
  }, []);

  const loadUserInfo = async () => {
    try {
      const userInfo = await authService.getCurrentUser();
      setUser(userInfo);
    } catch (err) {
      console.error('Failed to load user info', err);
    }
  };

  const loadBookings = async () => {
    setLoading(true);
    setError('');
    try {
      const data = await adminService.getBookings();
      setBookings(data);
    } catch (err) {
      setError(err.error || 'Failed to load bookings');
    } finally {
      setLoading(false);
    }
  };

  const handleLogout = async () => {
    try {
      await authService.logout();
      navigate('/login');
    } catch (err) {
      console.error('Logout failed', err);
      // Navigate to login anyway
      navigate('/login');
    }
  };

  const handleViewBooking = (bookingId) => {
    navigate(`/booking/${bookingId}`);
  };

  // Pagination logic
  const indexOfLastItem = currentPage * itemsPerPage;
  const indexOfFirstItem = indexOfLastItem - itemsPerPage;
  const currentBookings = bookings.slice(indexOfFirstItem, indexOfLastItem);
  const totalPages = Math.ceil(bookings.length / itemsPerPage);

  const formatDate = (timestamp) => {
    if (!timestamp) return 'N/A';
    return new Date(timestamp).toLocaleString();
  };

  return (
    <div className="dashboard-container">
      <div className="dashboard-header">
        <h1>Booking Management Dashboard</h1>
        <div className="user-info">
          {user && (
            <div className="user-badge">
              {user.username} ({user.role})
            </div>
          )}
          <button onClick={handleLogout} className="logout-button">
            Logout
          </button>
        </div>
      </div>

      {loading ? (
        <div className="loading">Loading bookings...</div>
      ) : error ? (
        <div className="error-message">{error}</div>
      ) : (
        <>
          <div className="bookings-table-container">
            <table className="bookings-table">
              <thead>
                <tr>
                  <th>Booking ID</th>
                  <th>Guest Name</th>
                  <th>Room Type</th>
                  <th>Check-in</th>
                  <th>Check-out</th>
                  <th>Created By</th>
                  <th>Created At</th>
                  <th>Access Level</th>
                  <th>Actions</th>
                </tr>
              </thead>
              <tbody>
                {currentBookings.length === 0 ? (
                  <tr>
                    <td colSpan="9" className="no-data">
                      No bookings found
                    </td>
                  </tr>
                ) : (
                  currentBookings.map((booking) => (
                    <tr key={booking.id}>
                      <td>{booking.id}</td>
                      <td>{booking.guest}</td>
                      <td>{booking.room}</td>
                      <td>{booking.checkin}</td>
                      <td>{booking.checkout}</td>
                      <td>{booking.createdBy || 'N/A'}</td>
                      <td>{formatDate(booking.createdAt)}</td>
                      <td>{booking.accessLevel || 'USER'}</td>
                      <td>
                        <button
                          onClick={() => handleViewBooking(booking.id)}
                          className="view-button"
                        >
                          View Details
                        </button>
                      </td>
                    </tr>
                  ))
                )}
              </tbody>
            </table>
          </div>

          {totalPages > 1 && (
            <div style={{ textAlign: 'center', marginTop: '20px' }}>
              {Array.from({ length: totalPages }, (_, i) => (
                <button
                  key={i + 1}
                  onClick={() => setCurrentPage(i + 1)}
                  style={{
                    margin: '0 5px',
                    padding: '8px 12px',
                    backgroundColor: currentPage === i + 1 ? '#667eea' : '#ddd',
                    color: currentPage === i + 1 ? 'white' : '#333',
                    border: 'none',
                    borderRadius: '5px',
                    cursor: 'pointer'
                  }}
                >
                  {i + 1}
                </button>
              ))}
            </div>
          )}
        </>
      )}
    </div>
  );
}

export default Dashboard;
