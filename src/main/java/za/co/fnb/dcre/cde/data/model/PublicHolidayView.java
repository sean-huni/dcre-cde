package za.co.fnb.dcre.cde.data.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDate;
import java.util.UUID;

/** Read model over HCS's public_holiday (grants-based, R-04/R-06). */
@Table("public_holiday")
public class PublicHolidayView {

    @Id
    private UUID id;
    private String country;
    private LocalDate holidayDate;

    public String getCountry() { return country; }
    public LocalDate getHolidayDate() { return holidayDate; }
}
