import axios from 'axios';

const API_BASE_URL = 'http://localhost:8080/api';

// Configure axios to send credentials (cookies) with requests
axios.defaults.withCredentials = true;

const adminService = {
  getBookings: async () => {
    try {
      const response = await axios.get(`${API_BASE_URL}/admin/bookings`);
      return response.data;
    } catch (error) {
      throw error.response?.data || { error: 'Failed to fetch bookings' };
    }
  },

  getBookingById: async (id) => {
    try {
      const response = await axios.get(`${API_BASE_URL}/admin/bookings/${id}`);
      return response.data;
    } catch (error) {
      throw error.response?.data || { error: 'Failed to fetch booking details' };
    }
  }
};

export default adminService;
