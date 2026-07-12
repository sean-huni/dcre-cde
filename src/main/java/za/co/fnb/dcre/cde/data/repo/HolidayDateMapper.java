package za.co.fnb.dcre.cde.data.repo;

import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;

public class HolidayDateMapper implements RowMapper<LocalDate> {

    @Override
    public LocalDate mapRow(ResultSet r, int rowNum) throws SQLException {
        return r.getObject("holiday_date", LocalDate.class);
    }
}
