function matsli_messages_summary(event) {
    let button = event.target;
    let parentSpan = button.closest("span")
    parentSpan.querySelector(".matsli_msgs_details").classList.add("matsli_noshow");
    parentSpan.querySelector(".matsli_msgs_details_btn").classList.remove("matsli_msgs_summary_or_details_btn_active");
    parentSpan.querySelector(".matsli_msgs_summary").classList.remove("matsli_noshow");
    parentSpan.querySelector(".matsli_msgs_summary_btn").classList.add("matsli_msgs_summary_or_details_btn_active");

}
function matsli_messages_details(event) {
    let button = event.target;
    let parentSpan = button.closest("span")
    parentSpan.querySelector(".matsli_msgs_details").classList.remove("matsli_noshow");
    parentSpan.querySelector(".matsli_msgs_details_btn").classList.add("matsli_msgs_summary_or_details_btn_active");
    parentSpan.querySelector(".matsli_msgs_summary").classList.add("matsli_noshow");
    parentSpan.querySelector(".matsli_msgs_summary_btn").classList.remove("matsli_msgs_summary_or_details_btn_active");
}