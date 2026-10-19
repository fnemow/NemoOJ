#include <nemo/json-lite.hpp>
#include <nlohmann/json.hpp>

namespace nemo_lite::api {
using Value = nlohmann::json;
static Value& ref(Handle handle) { return *static_cast<Value*>(handle); }
Handle null_value() { return new Value(nullptr); }
Handle boolean(bool value) { return new Value(value); }
Handle signed_integer(int64_t value) { return new Value(value); }
Handle unsigned_integer(uint64_t value) { return new Value(value); }
Handle floating(double value) { return new Value(value); }
Handle text(const std::string& value) { return new Value(value); }
Handle object() { return new Value(Value::object()); }
Handle array() { return new Value(Value::array()); }
Handle parse(const std::string& wire) { return new Value(Value::parse(wire)); }
Handle clone(Handle value) { return new Value(ref(value)); }
void destroy(Handle value) noexcept { delete static_cast<Value*>(value); }
bool is_null(Handle value) { return ref(value).is_null(); }
bool is_object(Handle value) { return ref(value).is_object(); }
bool is_array(Handle value) { return ref(value).is_array(); }
bool is_boolean(Handle value) { return ref(value).is_boolean(); }
bool is_integer(Handle value) { return ref(value).is_number_integer(); }
bool is_unsigned(Handle value) { return ref(value).is_number_unsigned(); }
bool is_number(Handle value) { return ref(value).is_number(); }
bool is_string(Handle value) { return ref(value).is_string(); }
int64_t get_signed(Handle value) { return ref(value).get<int64_t>(); }
uint64_t get_unsigned(Handle value) { return ref(value).get<uint64_t>(); }
double get_double(Handle value) { return ref(value).get<double>(); }
bool get_boolean(Handle value) { return ref(value).get<bool>(); }
std::string get_string(Handle value) { return ref(value).get<std::string>(); }
size_t size(Handle value) { return ref(value).size(); }
Handle at_key(Handle value, const std::string& key) { return new Value(ref(value).at(key)); }
Handle at_index(Handle value, size_t index) { return new Value(ref(value).at(index)); }
void push(Handle array, Handle value) { ref(array).push_back(ref(value)); }
void set(Handle object, const std::string& key, Handle value) { ref(object)[key] = ref(value); }
void erase_last(Handle value) { ref(value).erase(ref(value).size()-1); }
std::string dump(Handle value) { return ref(value).dump(); }
}
